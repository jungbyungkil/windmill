package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import com.windmill.dto.TransportMode;
import com.windmill.util.GeoUtils;
import com.windmill.util.LocationCacheKeys;
import com.windmill.util.SimpleTtlCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 다중 목적지 길찾기 - https://developers.kakaomobility.com/guide/navi-api/destinations */
    private static final String DESTINATIONS_URL = "https://apis-navi.kakaomobility.com/v1/destinations/directions";
    /** 문서상 목적지 최대 30개 */
    private static final int MAX_DESTINATIONS_PER_REQUEST = 30;
    /** radius는 필수 파라미터, 문서상 최대 10,000m */
    private static final int DESTINATIONS_RADIUS_METERS = 10_000;
    /**
     * 배치 실패분 1:1 재시도 전체에 대한 벽시계 상한 - 예전엔 Flux.flatMap(concurrency=4).blockLast
     * (45초/30초)가 이 상한 역할을 했는데, 배치 API 도입으로 순차 재시도로 바뀌면서 그 상한이
     * 사라졌었다(반경 10km 밖 목적지가 많으면 지점 수만큼 순차 10초 블로킹이 누적돼 사실상 무한정
     * 늘어남). 이 상한을 넘기면 남은 목적지는 즉시 Haversine 추정치로 채운다.
     */
    private static final Duration SINGLE_RETRY_BUDGET = Duration.ofSeconds(20);

    private final WebClient webClient;
    private final String restApiKey;
    // 좌표쌍(소수 4자리 반올림, ~11m) 단위 10분 TTL - greedy 스텝마다 후보 집합이 겹쳐도
    // 캐시 히트만큼 호출이 줄어든다. 값은 항상 minutes()를 쓸 수 있게 실제/추정을 구분해 담는다.
    private final SimpleTtlCache<String, DestinationEta> etaCache = new SimpleTtlCache<>(Duration.ofMinutes(10));

    public KakaoDirectionsClient(WebClient.Builder webClientBuilder,
                                 @Value("${kakao.rest-api-key:}") String restApiKey) {
        this.webClient = webClientBuilder.build();
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
    }

    public boolean isConfigured() {
        return !restApiKey.isBlank();
    }

    /**
     * 출발지 1개 → 목적지 여럿의 실제 도로 거리/시간 - 목적지가 30개를 넘으면 청크로 나눠 호출한다.
     * 반경(10km) 밖이거나 실패한 목적지만 1:1 길찾기로 재시도하고, 그것도 실패하면 Haversine 추정치로
     * 채우되 {@link DestinationEta#ok()}를 false로 표시한다 - 호출자는 순위 매김에는 써도 화면에
     * 보여주는 수치에는 ok=false 항목을 넣지 않아야 한다("틀린 숫자를 보여주지 않는 것이 핵심").
     */
    public List<DestinationEta> etaListFromOrigin(MapRouteRequest.MapPoint origin,
                                                  List<MapRouteRequest.MapPoint> points) {
        int n = points == null ? 0 : points.size();
        if (origin == null || n == 0) {
            return List.of();
        }
        DestinationEta[] out = new DestinationEta[n];
        if (!isConfigured()) {
            for (int i = 0; i < n; i++) {
                out[i] = haversineEta(origin, points.get(i));
            }
            return Arrays.asList(out);
        }

        List<Integer> uncached = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            DestinationEta cached = etaCache.get(pairKey(origin, points.get(i)));
            if (cached != null) {
                out[i] = cached;
            } else {
                uncached.add(i);
            }
        }
        if (!uncached.isEmpty()) {
            Map<Integer, DestinationEta> fetched = fetchBatchedEta(origin, points, uncached);
            for (Map.Entry<Integer, DestinationEta> e : fetched.entrySet()) {
                out[e.getKey()] = e.getValue();
                etaCache.put(pairKey(origin, points.get(e.getKey())), e.getValue());
            }
        }
        long retryDeadlineNanos = System.nanoTime() + SINGLE_RETRY_BUDGET.toNanos();
        for (int i = 0; i < n; i++) {
            if (out[i] != null) {
                continue;
            }
            if (System.nanoTime() >= retryDeadlineNanos) {
                // 벽시계 상한 초과 - 남은 목적지는 더 이상 1:1 재시도하지 않고 바로 추정치로 채운다.
                out[i] = haversineEta(origin, points.get(i));
                continue;
            }
            DestinationEta single = singleEta(origin, points.get(i));
            if (single != null) {
                out[i] = single;
                etaCache.put(pairKey(origin, points.get(i)), single);
            } else {
                out[i] = haversineEta(origin, points.get(i));
            }
        }
        return Arrays.asList(out);
    }

    /**
     * 순서대로 이어지는 경로의 각 구간(leg) 실제 거리·시간 - path[i]→path[i+1]. 화면에 보여줄
     * "before/after" 총 거리·소요시간을 정확히 계산할 때 쓴다(순서 결정 자체는 그대로 Haversine).
     * 각 구간은 {@link #etaListFromOrigin}과 같은 좌표쌍 캐시를 타므로, 서로 다른 두 순서(현재/재배치)
     * 가 구간을 공유해도(예: 앵커→A) 호출이 중복되지 않는다.
     */
    public List<DestinationEta> etaSequential(List<MapRouteRequest.MapPoint> path) {
        if (path == null || path.size() < 2) {
            return List.of();
        }
        List<DestinationEta> out = new ArrayList<>(path.size() - 1);
        for (int i = 0; i + 1 < path.size(); i++) {
            List<DestinationEta> single = etaListFromOrigin(path.get(i), List.of(path.get(i + 1)));
            out.add(single.isEmpty() ? haversineEta(path.get(i), path.get(i + 1)) : single.get(0));
        }
        return out;
    }

    private Map<Integer, DestinationEta> fetchBatchedEta(MapRouteRequest.MapPoint origin,
                                                          List<MapRouteRequest.MapPoint> points,
                                                          List<Integer> indices) {
        Map<Integer, DestinationEta> result = new HashMap<>();
        for (int start = 0; start < indices.size(); start += MAX_DESTINATIONS_PER_REQUEST) {
            List<Integer> chunk = indices.subList(start, Math.min(start + MAX_DESTINATIONS_PER_REQUEST, indices.size()));
            result.putAll(callDestinationsDirections(origin, points, chunk));
        }
        return result;
    }

    private Map<Integer, DestinationEta> callDestinationsDirections(MapRouteRequest.MapPoint origin,
                                                                     List<MapRouteRequest.MapPoint> points,
                                                                     List<Integer> indices) {
        Map<String, Object> originMap = new LinkedHashMap<>();
        originMap.put("x", origin.getLon());
        originMap.put("y", origin.getLat());

        List<Map<String, Object>> destList = new ArrayList<>();
        for (int idx : indices) {
            MapRouteRequest.MapPoint p = points.get(idx);
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("key", String.valueOf(idx));
            dm.put("x", p.getLon());
            dm.put("y", p.getLat());
            destList.add(dm);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", originMap);
        body.put("destinations", destList);
        body.put("radius", DESTINATIONS_RADIUS_METERS);
        body.put("priority", "TIME");
        // 0 = 유고(공사·사고·행사 등 전체 차선 통제) 정보를 경로 탐색에 반영(문서 기본값) - 실시간
        // 변수 대응 컨셉과 맞아 명시적으로 고정한다. 통제 중인 도로가 있으면 순서·시간이 자동으로 달라짐.
        body.put("roadevent", 0);

        try {
            JsonNode root = webClient.post()
                    .uri(DESTINATIONS_URL)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(15));
            return parseDestinationsResponse(root);
        } catch (Exception e) {
            log.warn("[KakaoDirections] destinations/directions 실패, 개별 재시도로 폴백: {}", e.toString());
            return Map.of();
        }
    }

    private Map<Integer, DestinationEta> parseDestinationsResponse(JsonNode root) {
        Map<Integer, DestinationEta> out = new HashMap<>();
        JsonNode routes = root == null ? null : root.path("routes");
        if (routes != null && routes.isArray()) {
            for (JsonNode route : routes) {
                int idx = route.path("key").asInt(-1);
                if (idx < 0) {
                    continue;
                }
                int code = route.path("result_code").asInt(-1);
                if (code != 0) {
                    continue; // 반경 밖·경로 없음 등 - 호출자가 1:1 재시도
                }
                JsonNode summary = route.path("summary");
                Integer dist = summary.has("distance") ? summary.get("distance").asInt() : null;
                Integer dur = summary.has("duration") ? summary.get("duration").asInt() : null;
                if (dur != null) {
                    out.put(idx, new DestinationEta(true, dist, dur));
                }
            }
        }
        return out;
    }

    /** 배치 실패분 1:1 재시도 - 거리는 못 받고 소요시간만(기존 길찾기 API는 summary=true로 duration만 조회). */
    private DestinationEta singleEta(MapRouteRequest.MapPoint origin, MapRouteRequest.MapPoint dest) {
        try {
            Integer sec = durationSeconds(origin, dest).block(Duration.ofSeconds(10));
            return sec == null ? null : new DestinationEta(true, null, sec);
        } catch (Exception e) {
            return null;
        }
    }

    private DestinationEta haversineEta(MapRouteRequest.MapPoint a, MapRouteRequest.MapPoint b) {
        int minutes = haversineMinutes(a, b);
        Double km = GeoUtils.distanceKmSafe(
                String.valueOf(a.getLon()), String.valueOf(a.getLat()),
                String.valueOf(b.getLon()), String.valueOf(b.getLat()));
        Integer meters = km == null ? null : (int) Math.round(km * 1000);
        return new DestinationEta(false, meters, minutes * 60);
    }

    /** 좌표쌍 캐시 키 - 소수 4자리(~11m)로 반올림해 LocationCacheKeys의 Locale.US 포맷을 그대로 쓴다
     * (로케일별 소수 구분자 차이로 캐시 키가 흔들리지 않게). */
    private static String pairKey(MapRouteRequest.MapPoint a, MapRouteRequest.MapPoint b) {
        return LocationCacheKeys.formatCoord(a.getLon(), 4) + "," + LocationCacheKeys.formatCoord(a.getLat(), 4)
                + "|" + LocationCacheKeys.formatCoord(b.getLon(), 4) + "," + LocationCacheKeys.formatCoord(b.getLat(), 4);
    }

    /**
     * @param ok             true면 카카오 실제 도로 데이터, false면 반경 밖·오류로 Haversine 추정
     * @param distanceMeters 실제 거리(ok=true일 때만 신뢰) - 1:1 재시도 성공 시엔 duration만 받아 null일 수 있음
     * @param durationSeconds 항상 값이 있음(실제 또는 추정) - {@link #minutes()}로 분 단위 조회
     */
    public record DestinationEta(boolean ok, Integer distanceMeters, int durationSeconds) {
        public int minutes() {
            return Math.max(1, (int) Math.ceil(durationSeconds / 60.0));
        }
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
     * 지점 간 자동차 이동시간(분) 매트릭스. 행(i)마다 다중 목적지 길찾기 1콜로 나머지 전부를
     * 조회한다(n행 = n콜, 예전엔 n(n-1)회 개별 호출). 각 콜은 {@link #etaListFromOrigin}과 같은
     * 좌표쌍 캐시를 타므로, 같은 일정을 반복 재계산해도(10분 내) 캐시 히트로 호출이 줄어든다.
     * 실패·미설정 구간은 Haversine×분/km 폴백.
     *
     * @param points lon/lat 순서 동일 인덱스
     * @return minutes[i][j] = i→j 분 (대각 0). roadBased는 실제 도로 데이터를 하나라도 구했는지
     */
    public TravelTimeMatrix buildTravelTimeMatrix(List<MapRouteRequest.MapPoint> points) {
        int n = points == null ? 0 : points.size();
        int[][] minutes = new int[n][n];
        if (n <= 1) {
            return new TravelTimeMatrix(minutes, 0, 0, false);
        }

        int roadOk = 0;
        int totalPairs = 0;
        for (int i = 0; i < n; i++) {
            List<MapRouteRequest.MapPoint> others = new ArrayList<>(points);
            MapRouteRequest.MapPoint origin = others.remove(i);
            List<DestinationEta> etas = etaListFromOrigin(origin, others);
            int oi = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                DestinationEta eta = etas.get(oi++);
                minutes[i][j] = eta.minutes();
                totalPairs++;
                if (eta.ok()) {
                    roadOk++;
                }
            }
        }
        if (roadOk > 0) {
            log.info("[KakaoDirections] travel matrix {}x{} roadOk={}/{}", n, n, roadOk, totalPairs);
        }
        return new TravelTimeMatrix(minutes, roadOk, totalPairs, roadOk > 0);
    }

    /**
     * GPS 등 외부 시작점 → 각 지점 이동시간(분). 내부적으로 {@link #etaListFromOrigin}(다중 목적지
     * 길찾기 1콜 + 캐시)을 쓴다 - 예전엔 지점 수만큼 1:1 길찾기를 개별 호출했다.
     */
    public int[] minutesFromOrigin(MapRouteRequest.MapPoint origin, List<MapRouteRequest.MapPoint> points) {
        int n = points == null ? 0 : points.size();
        int[] out = new int[n];
        if (origin == null || n == 0) {
            return out;
        }
        List<DestinationEta> etas = etaListFromOrigin(origin, points);
        for (int i = 0; i < n; i++) {
            out[i] = etas.get(i).minutes();
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
