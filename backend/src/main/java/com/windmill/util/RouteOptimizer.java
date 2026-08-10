package com.windmill.util;

import com.windmill.dto.RecommendationCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * 추천 후보 동선 최적화 — Haversine 순열 전수조사(소수 n) / NN 폴백.
 * 시작점은 여유율(혼잡↓)이 가장 좋은 곳으로 고정한 뒤 나머지를 최단으로 잇는다.
 */
public final class RouteOptimizer {

    private RouteOptimizer() {
    }

    public static List<RecommendationCandidate> optimize(List<RecommendationCandidate> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates == null ? List.of() : new ArrayList<>(candidates);
        }

        List<RecommendationCandidate> withCoords = new ArrayList<>();
        List<RecommendationCandidate> withoutCoords = new ArrayList<>();
        for (RecommendationCandidate c : candidates) {
            if (hasCoords(c)) {
                withCoords.add(c);
            } else {
                withoutCoords.add(c);
            }
        }
        if (withCoords.isEmpty()) {
            return new ArrayList<>(candidates);
        }

        // 시작점: 여유율(혼잡↓)이 가장 좋은 곳 — 제품 목표와 맞춤
        RecommendationCandidate start = withCoords.stream()
                .min(RouteOptimizer::compareCrowdThenName)
                .orElse(withCoords.get(0));

        List<RecommendationCandidate> rest = new ArrayList<>(withCoords);
        rest.remove(start);
        List<RecommendationCandidate> ordered = new ArrayList<>();
        ordered.add(start);
        if (!rest.isEmpty()) {
            ordered.addAll(VisitOrderOptimizer.optimizeFromOrigin(
                    rest, start.getMapX(), start.getMapY(),
                    RecommendationCandidate::getMapX, RecommendationCandidate::getMapY));
        }

        totalDistanceKm(ordered);
        ordered.addAll(withoutCoords);
        return ordered;
    }

    /** 순서대로 이어지는 구간 거리 합(km). 좌표 없는 구간은 무시 */
    public static double totalDistanceKm(List<RecommendationCandidate> ordered) {
        double sum = VisitOrderOptimizer.pathDistanceKm(
                ordered, null, null,
                RecommendationCandidate::getMapX, RecommendationCandidate::getMapY);
        if (ordered != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (i == 0) {
                    ordered.get(0).setDistanceKm(null);
                    continue;
                }
                RecommendationCandidate prev = ordered.get(i - 1);
                RecommendationCandidate cur = ordered.get(i);
                Double d = GeoUtils.distanceKmSafe(prev.getMapX(), prev.getMapY(), cur.getMapX(), cur.getMapY());
                cur.setDistanceKm(d == null ? null : Math.round(d * 10.0) / 10.0);
            }
        }
        return sum;
    }

    private static int compareCrowdThenName(RecommendationCandidate a, RecommendationCandidate b) {
        Double ca = a.getCrowdRate();
        Double cb = b.getCrowdRate();
        if (ca == null && cb == null) {
            return String.valueOf(a.getPlaceName()).compareTo(String.valueOf(b.getPlaceName()));
        }
        if (ca == null) {
            return 1;
        }
        if (cb == null) {
            return -1;
        }
        return Double.compare(ca, cb);
    }

    private static boolean hasCoords(RecommendationCandidate c) {
        return c.getMapX() != null && !c.getMapX().isBlank()
                && c.getMapY() != null && !c.getMapY().isBlank();
    }
}
