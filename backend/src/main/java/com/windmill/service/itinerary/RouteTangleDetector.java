package com.windmill.service.itinerary;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.RouteTangleResult;
import com.windmill.util.VisitOrderOptimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 저장된 일정 항목의 좌표로 동선 꼬임(비효율)을 감지하고,
 * Haversine 기반 순열 전수조사(소수 n)로 순서를 최적화한다.
 */
public final class RouteTangleDetector {

    /** 최적 대비 40% 이상 길면 꼬임으로 본다 */
    public static final double WASTE_RATIO_THRESHOLD = 1.4;
    private static final int MIN_POINTS = 3;

    private RouteTangleDetector() {
    }

    public static RouteTangleResult detect(List<ItineraryItem> items) {
        List<ItineraryItem> withCoords = items.stream()
                .filter(RouteTangleDetector::hasCoords)
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        if (withCoords.size() < MIN_POINTS) {
            return RouteTangleResult.builder().tangled(false).message(null).build();
        }

        double current = VisitOrderOptimizer.pathDistanceKm(
                withCoords, null, null, ItineraryItem::getMapX, ItineraryItem::getMapY);
        List<ItineraryItem> optimized = VisitOrderOptimizer.optimize(
                withCoords, ItineraryItem::getMapX, ItineraryItem::getMapY);
        double best = VisitOrderOptimizer.pathDistanceKm(
                optimized, null, null, ItineraryItem::getMapX, ItineraryItem::getMapY);
        if (best <= 0.01) {
            return RouteTangleResult.builder()
                    .tangled(false)
                    .currentDistanceKm(round(current))
                    .optimizedDistanceKm(round(best))
                    .build();
        }
        double ratio = current / best;
        boolean tangled = ratio >= WASTE_RATIO_THRESHOLD;
        return RouteTangleResult.builder()
                .tangled(tangled)
                .currentDistanceKm(round(current))
                .optimizedDistanceKm(round(best))
                .wasteRatio(Math.round(ratio * 100.0) / 100.0)
                .message(tangled
                        ? String.format("동선이 꼬였어요. 지금 %.1fkm → 재배치 시 약 %.1fkm로 줄일 수 있어요.", current, best)
                        : null)
                .build();
    }

    /** 순열(또는 NN)로 재정렬. 좌표 없는 항목은 맨 뒤. */
    public static List<ItineraryItem> optimizeOrder(List<ItineraryItem> items) {
        return optimizeOrderFromOrigin(items, null, null);
    }

    /**
     * GPS 등 시작점(경도/위도 문자열)을 0번으로 두고 나머지 순서를 최단으로 잡는다.
     */
    public static List<ItineraryItem> optimizeOrderFromOrigin(List<ItineraryItem> items,
                                                              String originLon,
                                                              String originLat) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<ItineraryItem> sorted = items.stream()
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        if (sorted.stream().filter(RouteTangleDetector::hasCoords).count() < 2) {
            return new ArrayList<>(sorted);
        }
        return VisitOrderOptimizer.optimizeFromOrigin(
                sorted, originLon, originLat, ItineraryItem::getMapX, ItineraryItem::getMapY);
    }

    private static boolean hasCoords(ItineraryItem item) {
        return item.getMapX() != null && !item.getMapX().isBlank()
                && item.getMapY() != null && !item.getMapY().isBlank();
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
