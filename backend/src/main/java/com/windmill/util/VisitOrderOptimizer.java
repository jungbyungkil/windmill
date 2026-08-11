package com.windmill.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 소수 장소(당일치기 3~5곳) 방문 순서 최적화.
 * n ≤ {@link #BRUTE_FORCE_LIMIT} 이면 순열 전수조사로 열린 경로 최단을 고르고,
 * 그보다 크면 최근접 이웃으로 폴백한다.
 * <p>
 * 기본 거리: Haversine 직선거리(km).
 * 카카오 이동시간 매트릭스가 있으면 {@link #optimizeWithTravelMinutes}로 분 단위 TSP.
 */
public final class VisitOrderOptimizer {

    /** 8! = 40320 — 수 ms 수준 */
    public static final int BRUTE_FORCE_LIMIT = 8;

    private VisitOrderOptimizer() {
    }

    /**
     * 이동시간(분) 매트릭스로 열린 TSP.
     *
     * @param items           좌표 있는 장소 (매트릭스 인덱스와 동일 순서)
     * @param minutesBetween  minutes[i][j] = i→j
     * @param minutesFromOrigin null이거나 length=n — GPS→i 분
     */
    public static <T> List<T> optimizeWithTravelMinutes(List<T> items,
                                                        int[][] minutesBetween,
                                                        int[] minutesFromOrigin) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        int n = items.size();
        if (n == 1) {
            return new ArrayList<>(items);
        }
        boolean useOrigin = minutesFromOrigin != null && minutesFromOrigin.length >= n;
        if (n <= BRUTE_FORCE_LIMIT) {
            return bruteForceMinutes(items, minutesBetween, useOrigin ? minutesFromOrigin : null);
        }
        return nearestNeighborMinutes(items, minutesBetween, useOrigin ? minutesFromOrigin : null);
    }

    private static <T> List<T> bruteForceMinutes(List<T> points, int[][] minutes, int[] fromOrigin) {
        int n = points.size();
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        double bestCost = Double.POSITIVE_INFINITY;
        int[] bestPerm = perm.clone();
        do {
            double cost = 0;
            if (fromOrigin != null) {
                cost += fromOrigin[perm[0]];
            }
            for (int i = 1; i < n; i++) {
                cost += minutes[perm[i - 1]][perm[i]];
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestPerm = perm.clone();
            }
        } while (nextPermutation(perm));

        List<T> ordered = new ArrayList<>(n);
        for (int idx : bestPerm) {
            ordered.add(points.get(idx));
        }
        return ordered;
    }

    private static <T> List<T> nearestNeighborMinutes(List<T> points, int[][] minutes, int[] fromOrigin) {
        int n = points.size();
        boolean[] used = new boolean[n];
        List<T> ordered = new ArrayList<>(n);
        int current;
        if (fromOrigin != null) {
            current = argMin(fromOrigin, used);
        } else {
            current = 0;
        }
        used[current] = true;
        ordered.add(points.get(current));
        for (int k = 1; k < n; k++) {
            int next = -1;
            int best = Integer.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (used[j]) {
                    continue;
                }
                int d = minutes[current][j];
                if (d < best) {
                    best = d;
                    next = j;
                }
            }
            if (next < 0) {
                break;
            }
            used[next] = true;
            ordered.add(points.get(next));
            current = next;
        }
        return ordered;
    }

    private static int argMin(int[] values, boolean[] used) {
        int best = -1;
        int bestV = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (used[i]) {
                continue;
            }
            if (values[i] < bestV) {
                bestV = values[i];
                best = i;
            }
        }
        return best < 0 ? 0 : best;
    }

    /**
     * 좌표 있는 장소만 최단 열린 경로로 재정렬. 좌표 없는 항목은 맨 뒤에 유지 순서대로 붙인다.
     */
    public static <T> List<T> optimize(List<T> items,
                                       Function<T, String> lonFn,
                                       Function<T, String> latFn) {
        return optimizeFromOrigin(items, null, null, lonFn, latFn);
    }

    /**
     * GPS 등 외부 시작점(0번)을 고정하고, 나머지 장소 순서를 최단으로 잡는다.
     * originLon/Lat가 없으면 {@link #optimize}와 동일.
     */
    public static <T> List<T> optimizeFromOrigin(List<T> items,
                                                 String originLon,
                                                 String originLat,
                                                 Function<T, String> lonFn,
                                                 Function<T, String> latFn) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<T> withCoords = new ArrayList<>();
        List<T> without = new ArrayList<>();
        for (T item : items) {
            if (hasCoords(item, lonFn, latFn)) {
                withCoords.add(item);
            } else {
                without.add(item);
            }
        }
        if (withCoords.size() <= 1) {
            List<T> out = new ArrayList<>(withCoords);
            out.addAll(without);
            return out;
        }

        boolean useOrigin = originLon != null && !originLon.isBlank()
                && originLat != null && !originLat.isBlank();
        List<T> ordered = withCoords.size() <= BRUTE_FORCE_LIMIT
                ? bruteForce(withCoords, useOrigin ? originLon : null, useOrigin ? originLat : null, lonFn, latFn)
                : nearestNeighbor(withCoords, useOrigin ? originLon : null, useOrigin ? originLat : null, lonFn, latFn);
        ordered.addAll(without);
        return ordered;
    }

    /** 순서대로 이어지는 Haversine 합. origin이 있으면 origin→첫 장소도 포함 */
    public static <T> double pathDistanceKm(List<T> ordered,
                                            String originLon,
                                            String originLat,
                                            Function<T, String> lonFn,
                                            Function<T, String> latFn) {
        double sum = 0;
        if (ordered == null || ordered.isEmpty()) {
            return 0;
        }
        if (originLon != null && originLat != null) {
            T first = ordered.get(0);
            Double d0 = GeoUtils.distanceKmSafe(originLon, originLat, lonFn.apply(first), latFn.apply(first));
            if (d0 != null) {
                sum += d0;
            }
        }
        for (int i = 1; i < ordered.size(); i++) {
            T a = ordered.get(i - 1);
            T b = ordered.get(i);
            Double d = GeoUtils.distanceKmSafe(lonFn.apply(a), latFn.apply(a), lonFn.apply(b), latFn.apply(b));
            if (d != null) {
                sum += d;
            }
        }
        return Math.round(sum * 10.0) / 10.0;
    }

    private static <T> List<T> bruteForce(List<T> points,
                                          String originLon,
                                          String originLat,
                                          Function<T, String> lonFn,
                                          Function<T, String> latFn) {
        int n = points.size();
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        double bestCost = Double.POSITIVE_INFINITY;
        int[] bestPerm = perm.clone();
        do {
            double cost = costOfPerm(points, perm, originLon, originLat, lonFn, latFn);
            if (cost < bestCost) {
                bestCost = cost;
                bestPerm = perm.clone();
            }
        } while (nextPermutation(perm));

        List<T> ordered = new ArrayList<>(n);
        for (int idx : bestPerm) {
            ordered.add(points.get(idx));
        }
        return ordered;
    }

    private static <T> double costOfPerm(List<T> points,
                                         int[] perm,
                                         String originLon,
                                         String originLat,
                                         Function<T, String> lonFn,
                                         Function<T, String> latFn) {
        double sum = 0;
        T first = points.get(perm[0]);
        if (originLon != null && originLat != null) {
            Double d0 = GeoUtils.distanceKmSafe(originLon, originLat, lonFn.apply(first), latFn.apply(first));
            sum += d0 == null ? 1_000_000.0 : d0;
        }
        for (int i = 1; i < perm.length; i++) {
            T a = points.get(perm[i - 1]);
            T b = points.get(perm[i]);
            Double d = GeoUtils.distanceKmSafe(lonFn.apply(a), latFn.apply(a), lonFn.apply(b), latFn.apply(b));
            sum += d == null ? 1_000_000.0 : d;
        }
        return sum;
    }

    private static <T> List<T> nearestNeighbor(List<T> points,
                                               String originLon,
                                               String originLat,
                                               Function<T, String> lonFn,
                                               Function<T, String> latFn) {
        List<T> remaining = new ArrayList<>(points);
        List<T> ordered = new ArrayList<>();
        T current;
        if (originLon != null && originLat != null) {
            current = takeNearestTo(remaining, originLon, originLat, lonFn, latFn);
        } else {
            current = remaining.remove(0);
        }
        ordered.add(current);
        while (!remaining.isEmpty()) {
            T next = takeNearestTo(remaining, lonFn.apply(current), latFn.apply(current), lonFn, latFn);
            if (next == null) {
                ordered.addAll(remaining);
                break;
            }
            ordered.add(next);
            current = next;
        }
        return ordered;
    }

    private static <T> T takeNearestTo(List<T> remaining,
                                       String lon,
                                       String lat,
                                       Function<T, String> lonFn,
                                       Function<T, String> latFn) {
        int bestIdx = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < remaining.size(); i++) {
            T c = remaining.get(i);
            Double d = GeoUtils.distanceKmSafe(lon, lat, lonFn.apply(c), latFn.apply(c));
            double dist = d == null ? Double.POSITIVE_INFINITY : d;
            if (dist < best) {
                best = dist;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) {
            return null;
        }
        return remaining.remove(bestIdx);
    }

    private static <T> boolean hasCoords(T item, Function<T, String> lonFn, Function<T, String> latFn) {
        String lon = lonFn.apply(item);
        String lat = latFn.apply(item);
        return lon != null && !lon.isBlank() && lat != null && !lat.isBlank();
    }

    /** lexicographic next permutation; returns false when exhausted */
    static boolean nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = a.length - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        swap(a, i, j);
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            swap(a, l, r);
        }
        return true;
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
