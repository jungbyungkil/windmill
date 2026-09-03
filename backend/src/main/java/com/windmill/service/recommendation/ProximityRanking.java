package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 고정(pin)한 장소 기준 근접도 순위 부스트. attachDistance()가 이미 채워둔 distanceKm을
 * 구간(bucket)으로 나눠 안정 정렬한다. 파이프라인에서 PopularityRanking이 이 뒤에 적용되므로
 * 같은 인기순 안에서의 타이브레이커로만 남는다. origin이 없어 distanceKm이 전부 null이면 손대지 않는다.
 */
final class ProximityRanking {

    private static final double NEAR_KM = 1.5;
    private static final double MID_KM = 4.0;

    private ProximityRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates) {
        boolean hasAnyDistance = candidates.stream().anyMatch(c -> c.getDistanceKm() != null);
        if (!hasAnyDistance) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(ProximityRanking::bucket))
                .collect(Collectors.toList());
    }

    private static int bucket(RelatedCandidate c) {
        Double km = c.getDistanceKm();
        if (km == null) {
            return 2;
        }
        if (km <= NEAR_KM) {
            return 0;
        }
        if (km <= MID_KM) {
            return 1;
        }
        return 3;
    }
}
