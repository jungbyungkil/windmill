package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import com.windmill.dto.TransportMode;
import com.windmill.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 카카오 모빌리티 자동차 길찾기 프록시.
 * REST API 키는 서버에만 두고, 프론트에는 경로 좌표만 내려준다.
 * 키 없거나 실패 시 Haversine 직선 폴백.
 *
 * @see <a href="https://developers.kakaomobility.com">Kakao Mobility Directions</a>
 */
@Slf4j
@Component
public class KakaoDirectionsClient {

    private static final String DIRECTIONS_URL = "https://apis-navi.kakaomobility.com/v1/directions";
    /** origin + waypoints(≤5) + destination */
    private static final int MAX_POINTS_PER_REQUEST = 7;

    private final WebClient webClient;
    private final String restApiKey;

    public KakaoDirectionsClient(WebClient.Builder webClientBuilder,
                                 @Value("${kakao.rest-api-key:}") String restApiKey) {
        this.webClient = webClientBuilder.build();
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
    }

    public boolean isConfigured() {
        return !restApiKey.isBlank();
    }

    /** 도보 평균 속도(km/h) - 실제 도보 길찾기는 카카오 제휴 전용이라 미확보(브리프 참고) */
    private static final double WALK_KMH = 4.5;
    /** 대중교통 평균 속도(km/h, 환승·대기 포함 도심 근사치) - 실제 노선 API 미확보 */
    private static final double TRANSIT_KMH = 20.0;

    public Mono<MapRouteResponse> route(List<MapRouteRequest.MapPoint> points, TransportMode mode) {
        TransportMode m = mode == null ? TransportMode.CAR : mode;
        if (points == null || points.size() < 2) {
            return Mono.just(MapRouteResponse.builder()
                    .path(List.of())
                    .roadBased(false)
                    .mode(m)
                    .message("경로를 그리려면 좌표가 있는 장소가 2곳 이상 필요해요.")
                    .build());
        }
        if (m != TransportMode.CAR) {
            return Mono.just(speedEstimate(points, m));
        }
        if (!isConfigured()) {
            MapRouteResponse resp = straightFallback(points, "카카오 REST 키가 없어 직선으로 연결했어요.");
            resp.setMode(m);
            return Mono.just(resp);
        }
        return fetchRoadPath(points)
                .doOnNext(r -> r.setMode(m))
                .onErrorResume(e -> {
                    log.warn("[KakaoDirections] fallback to straight line: {}", e.toString());
                    MapRouteResponse resp = straightFallback(points, "길찾기를 불러오지 못해 직선으로 연결했어요.");
                    resp.setMode(m);
                    return Mono.just(resp);
                });
    }

    /**
     * 도보/대중교통 추정 — 제휴 전용 API 미확보 상태라 브리프의 MVP 대안(대안1)을 따라
     * 직선거리 + 평균 속도로 소요시간만 추정한다. 실제 경로가 아니므로 estimated=true.
     */
    private MapRouteResponse speedEstimate(List<MapRouteRequest.MapPoint> points, TransportMode mode) {
        double kmh = mode == TransportMode.WALK ? WALK_KMH : TRANSIT_KMH;
        List<MapRouteResponse.LatLng> path = new ArrayList<>();
        double totalKm = 0;
        MapRouteRequest.MapPoint prev = null;
        for (MapRouteRequest.MapPoint p : points) {
            path.add(MapRouteResponse.LatLng.builder().lat(p.getLat()).lng(p.getLon()).build());
            if (prev != null) {
                Double km = GeoUtils.distanceKmSafe(
                        String.valueOf(prev.getLon()), String.valueOf(prev.getLat()),
                        String.valueOf(p.getLon()), String.valueOf(p.getLat()));
                if (km != null) {
                    totalKm += km;
                }
            }
            prev = p;
        }
        String label = mode == TransportMode.WALK ? "도보" : "대중교통";
        return MapRouteResponse.builder()
                .path(path)
                .distanceMeters(totalKm > 0 ? (int) Math.round(totalKm * 1000) : null)
                .durationSeconds(totalKm > 0 ? (int) Math.round(totalKm / kmh * 3600) : null)
                .roadBased(false)
                .estimated(true)
                .mode(mode)
                .message(label + " 실제 경로 API는 아직 준비 중이라, 직선거리 기준으로 추정한 소요시간이에요.")
                .build();
    }

    private Mono<MapRouteResponse> fetchRoadPath(List<MapRouteRequest.MapPoint> points) {
        if (points.size() <= MAX_POINTS_PER_REQUEST) {
            return callDirections(points);
        }
        // 7곳 초과: 겹치지 않게 청크로 이어 붙임 (청크 경계 공유 1점)
        List<Mono<MapRouteResponse>> parts = new ArrayList<>();
        for (int start = 0; start < points.size() - 1; start += MAX_POINTS_PER_REQUEST - 1) {
            int end = Math.min(start + MAX_POINTS_PER_REQUEST, points.size());
            parts.add(callDirections(points.subList(start, end)));
            if (end >= points.size()) {
                break;
            }
        }
        return Mono.zip(parts, arr -> {
            List<MapRouteResponse.LatLng> path = new ArrayList<>();
            int dist = 0;
            int dur = 0;
            for (Object o : arr) {
                MapRouteResponse part = (MapRouteResponse) o;
                if (part.getPath() != null) {
                    for (MapRouteResponse.LatLng p : part.getPath()) {
                        if (path.isEmpty() || !nearlySame(path.get(path.size() - 1), p)) {
                            path.add(p);
                        }
                    }
                }
                if (part.getDistanceMeters() != null) {
                    dist += part.getDistanceMeters();
                }
                if (part.getDurationSeconds() != null) {
                    dur += part.getDurationSeconds();
                }
            }
            return MapRouteResponse.builder()
                    .path(path)
                    .distanceMeters(dist > 0 ? dist : null)
                    .durationSeconds(dur > 0 ? dur : null)
                    .roadBased(true)
                    .build();
        });
    }

    private Mono<MapRouteResponse> callDirections(List<MapRouteRequest.MapPoint> points) {
        MapRouteRequest.MapPoint origin = points.get(0);
        MapRouteRequest.MapPoint dest = points.get(points.size() - 1);
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(DIRECTIONS_URL)
                .queryParam("origin", formatPoint(origin))
                .queryParam("destination", formatPoint(dest))
                .queryParam("priority", "RECOMMEND")
                .queryParam("car_fuel", "GASOLINE")
                .queryParam("car_hipass", "false")
                .queryParam("summary", "false");
        if (points.size() > 2) {
            StringBuilder waypoints = new StringBuilder();
            for (int i = 1; i < points.size() - 1; i++) {
                if (i > 1) {
                    waypoints.append('|');
                }
                waypoints.append(formatPoint(points.get(i)));
            }
            builder.queryParam("waypoints", waypoints.toString());
        }
        URI uri = builder.encode().build().toUri();

        return webClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parseResponse)
                .flatMap(resp -> {
                    if (resp.getPath() == null || resp.getPath().isEmpty()) {
                        return Mono.error(new IllegalStateException("empty path from Kakao"));
                    }
                    return Mono.just(resp);
                });
    }

    private MapRouteResponse parseResponse(JsonNode root) {
        JsonNode routes = root.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            throw new IllegalStateException("no routes");
        }
        JsonNode route = routes.get(0);
        int code = route.path("result_code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("kakao result_code=" + code + " " + route.path("result_msg").asText());
        }
        List<MapRouteResponse.LatLng> path = new ArrayList<>();
        JsonNode sections = route.path("sections");
        if (sections.isArray()) {
            for (JsonNode section : sections) {
                JsonNode roads = section.path("roads");
                if (!roads.isArray()) {
                    continue;
                }
                for (JsonNode road : roads) {
                    JsonNode vertexes = road.path("vertexes");
                    if (!vertexes.isArray()) {
                        continue;
                    }
                    for (int i = 0; i + 1 < vertexes.size(); i += 2) {
                        double lng = vertexes.get(i).asDouble();
                        double lat = vertexes.get(i + 1).asDouble();
                        MapRouteResponse.LatLng p = MapRouteResponse.LatLng.builder().lat(lat).lng(lng).build();
                        if (path.isEmpty() || !nearlySame(path.get(path.size() - 1), p)) {
                            path.add(p);
                        }
                    }
                }
            }
        }
        JsonNode summary = route.path("summary");
        Integer distance = summary.has("distance") ? summary.get("distance").asInt() : null;
        Integer duration = summary.has("duration") ? summary.get("duration").asInt() : null;
        return MapRouteResponse.builder()
                .path(path)
                .distanceMeters(distance)
                .durationSeconds(duration)
                .roadBased(true)
                .build();
    }

    private static String formatPoint(MapRouteRequest.MapPoint p) {
        return p.getLon() + "," + p.getLat();
    }

    private static boolean nearlySame(MapRouteResponse.LatLng a, MapRouteResponse.LatLng b) {
        return Math.abs(a.getLat() - b.getLat()) < 1e-7 && Math.abs(a.getLng() - b.getLng()) < 1e-7;
    }

    static MapRouteResponse straightFallback(List<MapRouteRequest.MapPoint> points, String message) {
        List<MapRouteResponse.LatLng> path = new ArrayList<>();
        double meters = 0;
        int minutes = 0;
        MapRouteRequest.MapPoint prev = null;
        for (MapRouteRequest.MapPoint p : points) {
            path.add(MapRouteResponse.LatLng.builder().lat(p.getLat()).lng(p.getLon()).build());
            if (prev != null) {
                Double km = GeoUtils.distanceKmSafe(
                        String.valueOf(prev.getLon()), String.valueOf(prev.getLat()),
                        String.valueOf(p.getLon()), String.valueOf(p.getLat()));
                if (km != null) {
                    meters += km * 1000.0;
                }
                // TravelTimeMatrix와 동일한 "직선거리 → 분" 근사(haversineMinutes) 재사용 - 카카오 키가
                // 없거나 실패했을 때도 이동시간 기반 판정(마감 게이트·이동시간 트리거)이 계속 동작하도록 함
                minutes += haversineMinutes(prev, p);
            }
            prev = p;
        }
        return MapRouteResponse.builder()
                .path(path)
                .distanceMeters(meters > 0 ? (int) Math.round(meters) : null)
                .durationSeconds(minutes > 0 ? minutes * 60 : null)
                .roadBased(false)
                .message(message)
                .build();
    }

    /**
     * 지점 간 자동차 이동시간(분) 매트릭스.
     * n≤8 당일치기 기준 n(n-1)회 길찾기. 실패·미설정 시 Haversine×분/km 폴백.
     *
     * @param points lon/lat 순서 동일 인덱스
     * @return minutes[i][j] = i→j 분 (대각 0). roadBasedRatio는 카카오 성공 비율 힌트용
     */
    public TravelTimeMatrix buildTravelTimeMatrix(List<MapRouteRequest.MapPoint> points) {
        int n = points == null ? 0 : points.size();
        int[][] minutes = new int[n][n];
        if (n == 0) {
            return new TravelTimeMatrix(minutes, 0, 0, false);
        }
        if (!isConfigured() || n == 1) {
            fillHaversineMinutes(points, minutes);
            return new TravelTimeMatrix(minutes, 0, 0, false);
        }

        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    pairs.add(new int[]{i, j});
                }
            }
        }
        AtomicInteger ok = new AtomicInteger();
        Flux.fromIterable(pairs)
                .flatMap(pair -> {
                    int i = pair[0];
                    int j = pair[1];
                    return durationSeconds(points.get(i), points.get(j))
                            .map(sec -> {
                                minutes[i][j] = Math.max(1, (int) Math.ceil(sec / 60.0));
                                ok.incrementAndGet();
                                return true;
                            })
                            .onErrorResume(e -> {
                                minutes[i][j] = haversineMinutes(points.get(i), points.get(j));
                                return Mono.just(false);
                            });
                }, 4)
                .blockLast(Duration.ofSeconds(45));

        int totalPairs = pairs.size();
        int roadOk = ok.get();
        if (roadOk == 0) {
            fillHaversineMinutes(points, minutes);
            return new TravelTimeMatrix(minutes, 0, totalPairs, false);
        }
        // 빠진 칸만 직선 폴백
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && minutes[i][j] <= 0) {
                    minutes[i][j] = haversineMinutes(points.get(i), points.get(j));
                }
            }
        }
        log.info("[KakaoDirections] travel matrix {}x{} roadOk={}/{}", n, n, roadOk, totalPairs);
        return new TravelTimeMatrix(minutes, roadOk, totalPairs, roadOk > 0);
    }

    /**
     * GPS 등 외부 시작점 → 각 지점 이동시간(분).
     */
    public int[] minutesFromOrigin(MapRouteRequest.MapPoint origin, List<MapRouteRequest.MapPoint> points) {
        int n = points == null ? 0 : points.size();
        int[] out = new int[n];
        if (origin == null || n == 0) {
            return out;
        }
        if (!isConfigured()) {
            for (int i = 0; i < n; i++) {
                out[i] = haversineMinutes(origin, points.get(i));
            }
            return out;
        }
        Flux.range(0, n)
                .flatMap(i -> durationSeconds(origin, points.get(i))
                        .map(sec -> {
                            out[i] = Math.max(1, (int) Math.ceil(sec / 60.0));
                            return i;
                        })
                        .onErrorResume(e -> {
                            out[i] = haversineMinutes(origin, points.get(i));
                            return Mono.just(i);
                        }), 4)
                .blockLast(Duration.ofSeconds(30));
        for (int i = 0; i < n; i++) {
            if (out[i] <= 0) {
                out[i] = haversineMinutes(origin, points.get(i));
            }
        }
        return out;
    }

    private Mono<Integer> durationSeconds(MapRouteRequest.MapPoint from, MapRouteRequest.MapPoint to) {
        URI uri = UriComponentsBuilder
                .fromUriString(DIRECTIONS_URL)
                .queryParam("origin", formatPoint(from))
                .queryParam("destination", formatPoint(to))
                .queryParam("priority", "RECOMMEND")
                .queryParam("car_fuel", "GASOLINE")
                .queryParam("car_hipass", "false")
                .queryParam("summary", "true")
                .encode()
                .build()
                .toUri();
        return webClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(root -> {
                    JsonNode routes = root.path("routes");
                    if (!routes.isArray() || routes.isEmpty()) {
                        throw new IllegalStateException("no routes");
                    }
                    JsonNode route = routes.get(0);
                    if (route.path("result_code").asInt(-1) != 0) {
                        throw new IllegalStateException(route.path("result_msg").asText("kakao error"));
                    }
                    JsonNode summary = route.path("summary");
                    if (!summary.has("duration")) {
                        throw new IllegalStateException("no duration");
                    }
                    return summary.get("duration").asInt();
                });
    }

    private static void fillHaversineMinutes(List<MapRouteRequest.MapPoint> points, int[][] minutes) {
        for (int i = 0; i < points.size(); i++) {
            for (int j = 0; j < points.size(); j++) {
                if (i == j) {
                    minutes[i][j] = 0;
                } else {
                    minutes[i][j] = haversineMinutes(points.get(i), points.get(j));
                }
            }
        }
    }

    /** 직선거리 → 분 (약 12분/km, 10~90 클램프) */
    static int haversineMinutes(MapRouteRequest.MapPoint a, MapRouteRequest.MapPoint b) {
        Double km = GeoUtils.distanceKmSafe(
                String.valueOf(a.getLon()), String.valueOf(a.getLat()),
                String.valueOf(b.getLon()), String.valueOf(b.getLat()));
        if (km == null) {
            return 20;
        }
        int m = (int) Math.ceil(km * 12.0);
        return Math.max(10, Math.min(m, 90));
    }

    public record TravelTimeMatrix(int[][] minutes, int roadOkPairs, int totalPairs, boolean roadBased) {
        public int pathMinutes(int[] order, int[] fromOrigin) {
            if (order == null || order.length == 0) {
                return 0;
            }
            int sum = 0;
            if (fromOrigin != null && fromOrigin.length > order[0]) {
                sum += fromOrigin[order[0]];
            }
            for (int i = 1; i < order.length; i++) {
                sum += minutes[order[i - 1]][order[i]];
            }
            return sum;
        }
    }
}
