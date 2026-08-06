package com.windmill.util;

import com.windmill.dto.RecommendationCandidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 동선 꼬임 최소화 - 좌표가 있는 후보를 최근접 이웃(nearest-neighbor)으로 연결한다.
 * 완벽한 TSP는 아니지만 TourAPI 후보 소수(≤10)에서는 충분히 실용적이다.
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
                .min((a, b) -> compareCrowdThenName(a, b))
                .orElse(withCoords.get(0));

        List<RecommendationCandidate> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        RecommendationCandidate current = start;
        ordered.add(current);
        visited.add(key(current));

        while (ordered.size() < withCoords.size()) {
            RecommendationCandidate nearest = null;
            double best = Double.MAX_VALUE;
            for (RecommendationCandidate c : withCoords) {
                if (visited.contains(key(c))) {
                    continue;
                }
                Double d = GeoUtils.distanceKmSafe(current.getMapX(), current.getMapY(), c.getMapX(), c.getMapY());
                double dist = d == null ? Double.MAX_VALUE : d;
                if (dist < best) {
                    best = dist;
                    nearest = c;
                }
            }
            if (nearest == null) {
                break;
            }
            nearest.setDistanceKm(best == Double.MAX_VALUE ? null : best);
            ordered.add(nearest);
            visited.add(key(nearest));
            current = nearest;
        }

        // 첫 장소는 이전 구간 없음
        if (!ordered.isEmpty()) {
            ordered.get(0).setDistanceKm(null);
        }
        ordered.addAll(withoutCoords);
        return ordered;
    }

    /** 순서대로 이어지는 구간 거리 합(km). 좌표 없는 구간은 무시 */
    public static double totalDistanceKm(List<RecommendationCandidate> ordered) {
        double sum = 0;
        for (int i = 1; i < ordered.size(); i++) {
            RecommendationCandidate prev = ordered.get(i - 1);
            RecommendationCandidate cur = ordered.get(i);
            Double d = GeoUtils.distanceKmSafe(prev.getMapX(), prev.getMapY(), cur.getMapX(), cur.getMapY());
            if (d != null) {
                sum += d;
                cur.setDistanceKm(d);
            }
        }
        if (!ordered.isEmpty()) {
            ordered.get(0).setDistanceKm(null);
        }
        return Math.round(sum * 10.0) / 10.0;
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

    private static String key(RecommendationCandidate c) {
        return c.getContentId() != null ? c.getContentId() : c.getPlaceName();
    }
}
