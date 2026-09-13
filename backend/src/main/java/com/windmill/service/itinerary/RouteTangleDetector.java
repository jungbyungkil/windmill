package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.MapRouteRequest;
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
        return detect(items, null, null);
    }

    /**
     * @param anchorLon/anchorLat 출발 앵커(완료 항목 좌표·GPS 등) - 있으면 앵커→남은 구간 거리까지
     *                            포함해 "지금 Xkm → 재배치 시 Ykm" 절감 수치가 실제 남은 여정을 반영한다.
     */
    public static RouteTangleResult detect(List<ItineraryItem> items, String anchorLon, String anchorLat) {
        List<ItineraryItem> withCoords = items.stream()
                .filter(RouteTangleDetector::hasCoords)
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        if (withCoords.size() < MIN_POINTS) {
            return RouteTangleResult.builder().tangled(false).message(null).build();
        }

        boolean useAnchor = anchorLon != null && anchorLat != null;
        double current = VisitOrderOptimizer.pathDistanceKm(
                withCoords, useAnchor ? anchorLon : null, useAnchor ? anchorLat : null,
                ItineraryItem::getMapX, ItineraryItem::getMapY);
        List<ItineraryItem> optimized = useAnchor
                ? VisitOrderOptimizer.optimizeFromOrigin(withCoords, anchorLon, anchorLat,
                        ItineraryItem::getMapX, ItineraryItem::getMapY)
                : VisitOrderOptimizer.optimize(withCoords, ItineraryItem::getMapX, ItineraryItem::getMapY);
        double best = VisitOrderOptimizer.pathDistanceKm(
                optimized, useAnchor ? anchorLon : null, useAnchor ? anchorLat : null,
                ItineraryItem::getMapX, ItineraryItem::getMapY);
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

    /**
     * 순서 판정(tangled/wasteRatio)은 그대로 Haversine이지만, 화면에 보여주는 "지금 Xkm → Yikm"
     * 수치와 소요시간은 카카오 실제 도로 데이터로 다시 계산한다("직선거리 오차가 표시 수치에 그대로
     * 들어가면 근사가 아니라 틀린 숫자가 된다" - 사용자 피드백). 구간 중 하나라도 실제 데이터를
     * 못 받으면(반경 밖·API 실패) 기존 Haversine 표시를 그대로 유지한다 - 절반만 진짜인 숫자를
     * 보여주지 않는다.
     */
    public static RouteTangleResult detect(List<ItineraryItem> items, String anchorLon, String anchorLat,
                                           KakaoDirectionsClient client) {
        RouteTangleResult base = detect(items, anchorLon, anchorLat);
        if (client == null || !client.isConfigured() || !base.isTangled()) {
            return base;
        }
        List<ItineraryItem> withCoords = items.stream()
                .filter(RouteTangleDetector::hasCoords)
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        boolean useAnchor = anchorLon != null && anchorLat != null;
        List<ItineraryItem> optimizedOrder = useAnchor
                ? VisitOrderOptimizer.optimizeFromOrigin(withCoords, anchorLon, anchorLat,
                        ItineraryItem::getMapX, ItineraryItem::getMapY)
                : VisitOrderOptimizer.optimize(withCoords, ItineraryItem::getMapX, ItineraryItem::getMapY);

        RealLeg current = realDistanceOf(withCoords, anchorLon, anchorLat, client);
        RealLeg optimized = realDistanceOf(optimizedOrder, anchorLon, anchorLat, client);
        if (current == null || optimized == null) {
            return base;
        }

        String message = (current.km != null && optimized.km != null)
                ? String.format("동선이 꼬였어요. 지금 순서로는 약 %d분(%.1fkm) → 재배치하면 약 %d분(%.1fkm)으로 줄어요.",
                        current.minutes, current.km, optimized.minutes, optimized.km)
                : String.format("동선이 꼬였어요. 지금 순서로는 약 %d분 → 재배치하면 약 %d분으로 줄어요.",
                        current.minutes, optimized.minutes);
        return RouteTangleResult.builder()
                .tangled(true)
                .currentDistanceKm(current.km)
                .optimizedDistanceKm(optimized.km)
                .currentDurationMinutes(current.minutes)
                .optimizedDurationMinutes(optimized.minutes)
                .wasteRatio(base.getWasteRatio())
                .message(message)
                .build();
    }

    /** 앵커(있으면) → ordered 순서대로 이어지는 실제 거리·시간 합. 구간 하나라도 못 믿으면(반경 밖 등) null. */
    private static RealLeg realDistanceOf(List<ItineraryItem> ordered, String anchorLon, String anchorLat,
                                          KakaoDirectionsClient client) {
        List<MapRouteRequest.MapPoint> path = new ArrayList<>();
        if (anchorLon != null && anchorLat != null) {
            path.add(MapRouteRequest.MapPoint.builder()
                    .lon(Double.parseDouble(anchorLon)).lat(Double.parseDouble(anchorLat)).name("앵커").build());
        }
        for (ItineraryItem item : ordered) {
            path.add(MapRouteRequest.MapPoint.builder()
                    .lon(Double.parseDouble(item.getMapX().trim()))
                    .lat(Double.parseDouble(item.getMapY().trim()))
                    .name(item.getPlaceName())
                    .build());
        }
        if (path.size() < 2) {
            return new RealLeg(0.0, 0);
        }
        List<KakaoDirectionsClient.DestinationEta> legs = client.etaSequential(path);
        int totalSeconds = 0;
        Integer totalMeters = 0;
        for (KakaoDirectionsClient.DestinationEta leg : legs) {
            if (!leg.ok()) {
                return null; // 소요시간조차 못 믿으면 이 순서 전체 표시를 포기
            }
            totalSeconds += leg.durationSeconds();
            if (totalMeters != null && leg.distanceMeters() != null) {
                totalMeters += leg.distanceMeters();
            } else {
                totalMeters = null;
            }
        }
        Double km = totalMeters == null ? null : totalMeters / 1000.0;
        return new RealLeg(km, (int) Math.round(totalSeconds / 60.0));
    }

    private record RealLeg(Double km, int minutes) {
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
