package com.windmill.service.itinerary;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.RouteTangleResult;
import com.windmill.util.GeoUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 저장된 일정 항목의 좌표로 동선 꼬임(비효율)을 감지한다.
 * 현재 순서 총거리 / 최근접 최적화 총거리 비율이 임계치 이상이면 tangled.
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

        double current = pathDistance(withCoords);
        List<ItineraryItem> optimized = nearestNeighbor(withCoords);
        double best = pathDistance(optimized);
        if (best <= 0.01) {
            return RouteTangleResult.builder().tangled(false).currentDistanceKm(round(current)).optimizedDistanceKm(round(best)).build();
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

    /** 최근접 이웃으로 재정렬한 목록 (원본 리스트는 변경하지 않음) */
    public static List<ItineraryItem> optimizeOrder(List<ItineraryItem> items) {
        List<ItineraryItem> withCoords = items.stream()
                .filter(RouteTangleDetector::hasCoords)
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        List<ItineraryItem> without = items.stream()
                .filter(i -> !hasCoords(i))
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        if (withCoords.size() < 2) {
            return new ArrayList<>(items);
        }
        List<ItineraryItem> ordered = nearestNeighbor(withCoords);
        ordered.addAll(without);
        return ordered;
    }

    private static List<ItineraryItem> nearestNeighbor(List<ItineraryItem> points) {
        List<ItineraryItem> remaining = new ArrayList<>(points);
        List<ItineraryItem> ordered = new ArrayList<>();
        ItineraryItem current = remaining.remove(0);
        ordered.add(current);
        Set<Long> seen = new HashSet<>();
        seen.add(current.getId());
        while (!remaining.isEmpty()) {
            ItineraryItem best = null;
            double bestD = Double.MAX_VALUE;
            for (ItineraryItem c : remaining) {
                Double d = GeoUtils.distanceKmSafe(current.getMapX(), current.getMapY(), c.getMapX(), c.getMapY());
                double dist = d == null ? Double.MAX_VALUE : d;
                if (dist < bestD) {
                    bestD = dist;
                    best = c;
                }
            }
            if (best == null) {
                break;
            }
            remaining.remove(best);
            ordered.add(best);
            current = best;
            seen.add(best.getId());
        }
        return ordered;
    }

    private static double pathDistance(List<ItineraryItem> ordered) {
        double sum = 0;
        for (int i = 1; i < ordered.size(); i++) {
            ItineraryItem a = ordered.get(i - 1);
            ItineraryItem b = ordered.get(i);
            Double d = GeoUtils.distanceKmSafe(a.getMapX(), a.getMapY(), b.getMapX(), b.getMapY());
            if (d != null) {
                sum += d;
            }
        }
        return sum;
    }

    private static boolean hasCoords(ItineraryItem item) {
        return item.getMapX() != null && !item.getMapX().isBlank()
                && item.getMapY() != null && !item.getMapY().isBlank();
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
