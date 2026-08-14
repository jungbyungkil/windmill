package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 고정(pin)한 장소 기준 근접도 순위 부스트 - "장소를 고정하고 그 주변으로 검색"하는 사용자 의도를
 * 반영한다. attachDistance()가 이미 채워둔 distanceKm을 구간(bucket)으로 나눠 안정 정렬하므로,
 * 정확히 같은 구간(예: 반경 1.5km 이내끼리) 안에서는 CompanionCategoryRanking/AccessibilityRanking/
 * AgeGroupRanking이 정한 순서가 그대로 유지된다 - "근처인지"가 가장 중요하되, 그 안에서는 연령대·
 * 동반유형 등 개인화도 계속 반영되게 하기 위함(연속값 그대로 정렬하면 그 정보가 묻혀버림).
 * origin이 없어(distanceKm이 전부 null) 근접도를 알 수 없으면 손대지 않는다.
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
