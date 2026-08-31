package com.windmill.service.tourapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.HoursPhase;
import com.windmill.dto.NearbyPlaceResponse;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.util.ContentTypeLabels;
import com.windmill.util.KoreaClock;
import com.windmill.util.LocationCacheKeys;
import com.windmill.util.SimpleTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 지도 "이 지역 재검색" — locationBasedList2 프록시.
 * 혼잡도 캐시와 같은 30분 TTL. 캐시 키는 좌표 소수점 3자리 반올림.
 * 상세조회는 하지 않고, 이미 캐시된 detailIntro가 있을 때만 영업 3단계를 붙인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationBasedSearchService {

    static final int DEFAULT_RADIUS_M = 1000;
    static final int MIN_RADIUS_M = 100;
    static final int MAX_RADIUS_M = 20_000;
    static final int DEFAULT_ROWS = 40;
    static final int MAX_ROWS = 50;
    static final int DAILY_QUOTA = 1000;

    private final KorServiceClient korServiceClient;
    private final TourAttractionService tourAttractionService;

    private final SimpleTtlCache<String, List<NearbyPlaceResponse>> cache =
            new SimpleTtlCache<>(Duration.ofMinutes(30));

    private final AtomicInteger dailyCalls = new AtomicInteger();
    private final AtomicReference<LocalDate> callDay = new AtomicReference<>(KoreaClock.today());

    public Mono<List<NearbyPlaceResponse>> search(double mapX, double mapY, Integer radius,
                                                   Integer contentTypeId, Integer pageNo, Integer numOfRows) {
        if (!Double.isFinite(mapX) || !Double.isFinite(mapY)) {
            return Mono.error(new IllegalArgumentException("지도 좌표가 올바르지 않아요."));
        }
        int r = clamp(radius == null ? DEFAULT_RADIUS_M : radius, MIN_RADIUS_M, MAX_RADIUS_M);
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int rows = clamp(numOfRows == null ? DEFAULT_ROWS : numOfRows, 1, MAX_ROWS);
        String key = LocationCacheKeys.nearby(mapX, mapY, r, contentTypeId, page, rows);

        List<NearbyPlaceResponse> cached = cache.get(key);
        if (cached != null) {
            log.info("[KorService2] locationBasedList2 캐시 히트 key={} (실호출 없음, 일간 누적 {}/{})",
                    key, dailyCalls.get(), DAILY_QUOTA);
            return Mono.just(cached);
        }

        if (!korServiceClient.isConfigured()) {
            log.warn("[KorService2] locationBasedList2 스킵 - 서비스키 없음");
            return Mono.just(List.of());
        }

        int n = bumpDailyCalls();
        if (n > DAILY_QUOTA) {
            log.warn("[KorService2] locationBasedList2 실호출 {}/{} 한도 초과 임박 또는 초과 key={}",
                    n, DAILY_QUOTA, key);
        } else {
            log.info("[KorService2] locationBasedList2 실호출 {}/{} key={} radius={} page={} type={}",
                    n, DAILY_QUOTA, key, r, page, contentTypeId == null ? "all" : contentTypeId);
        }

        String qx = LocationCacheKeys.formatCoord(mapX);
        String qy = LocationCacheKeys.formatCoord(mapY);
        return korServiceClient.locationBasedList(qx, qy, r, contentTypeId, rows, page)
                .map(this::toPlaces)
                .doOnNext(list -> cache.put(key, list));
    }

    private List<NearbyPlaceResponse> toPlaces(List<JsonNode> items) {
        List<NearbyPlaceResponse> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (JsonNode node : items) {
            NearbyPlaceResponse place = toPlace(node);
            if (place != null) {
                out.add(place);
            }
        }
        return out;
    }

    private NearbyPlaceResponse toPlace(JsonNode node) {
        String contentId = text(node, "contentid", "contentId");
        String title = text(node, "title");
        String mapX = text(node, "mapx", "mapX");
        String mapY = text(node, "mapy", "mapY");
        if (contentId == null || title == null || mapX == null || mapY == null) {
            return null;
        }
        Integer typeId = ContentTypeLabels.parse(text(node, "contenttypeid", "contentTypeId"));
        String image = text(node, "firstimage", "firstImage");
        if (image == null) {
            image = text(node, "firstimage2", "firstImage2");
        }
        return NearbyPlaceResponse.builder()
                .contentId(contentId)
                .contentTypeId(typeId)
                .placeName(title)
                .addr1(text(node, "addr1"))
                .mapX(mapX)
                .mapY(mapY)
                .thumbnailUrl(image)
                .tel(text(node, "tel"))
                .cat1(text(node, "cat1"))
                .cat2(text(node, "cat2"))
                .cat3(text(node, "cat3"))
                .category(ContentTypeLabels.of(typeId))
                .dist(parseDist(node))
                .hoursPhase(hoursPhaseFromCache(contentId))
                .build();
    }

    /**
     * 추가 detailIntro 호출 없이, 이미 조회해 둔 소개정보가 있을 때만 3단계 영업상태를 붙인다.
     */
    private HoursPhase hoursPhaseFromCache(String contentId) {
        TourAttractionDetail detail = tourAttractionService.peekCachedDetail(contentId);
        if (detail == null || detail.getIntroFields() == null || detail.getIntroFields().isEmpty()) {
            return HoursPhase.UNKNOWN;
        }
        return BusinessHoursEvaluator.currentPhase(detail.getIntroFields());
    }

    private int bumpDailyCalls() {
        LocalDate today = KoreaClock.today();
        LocalDate prev = callDay.get();
        if (!today.equals(prev)) {
            if (callDay.compareAndSet(prev, today)) {
                dailyCalls.set(0);
            }
        }
        return dailyCalls.incrementAndGet();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String v = node.path(field).asText(null);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static Integer parseDist(JsonNode node) {
        String raw = text(node, "dist");
        if (raw == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
