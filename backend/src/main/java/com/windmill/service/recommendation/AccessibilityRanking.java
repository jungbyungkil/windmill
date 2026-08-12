package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 유모차/무장애 옵션별 순위 부스트 - withPet과 달리 전용 API가 없어 후보 소스 자체는 바꾸지 않고,
 * 안정 정렬(stable sort)로 조건에 맞는 후보만 앞으로 당긴다. 데이터가 부족(모름/false)해도 후보를
 * 제거하지 않아 결과가 비는 것을 막는다 - CompanionCategoryRanking과 동일한 설계 원칙.
 */
final class AccessibilityRanking {

    private AccessibilityRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates, boolean strollerFriendly, boolean accessibleFriendly) {
        if (!strollerFriendly && !accessibleFriendly) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt((RelatedCandidate c) -> score(c, strollerFriendly, accessibleFriendly)))
                .collect(Collectors.toList());
    }

    private static int score(RelatedCandidate c, boolean strollerFriendly, boolean accessibleFriendly) {
        int score = 0;
        if (strollerFriendly && !Boolean.TRUE.equals(c.getStrollerFriendly())) {
            score++;
        }
        if (accessibleFriendly && !c.isAccessibleFriendly()) {
            score++;
        }
        return score;
    }
}
