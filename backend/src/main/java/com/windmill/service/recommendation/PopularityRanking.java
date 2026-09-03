package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 지역 인기순(TourAPI 조회순 rank → 집중률)을 1순위 정렬 키로 둔다.
 * Companion/Age/Proximity 등 다른 순위 보정은 이보다 먼저 적용하고, 파이프라인에서
 * 이 클래스를 마지막에 호출해 인기순이 ORDER BY의 제일 앞이 되게 한다.
 * rank가 비어 있거나 0이면 조회순을 모르는 후보로 보고 뒤로 보낸다.
 */
final class PopularityRanking {

    private PopularityRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return candidates;
        }
        return candidates.stream()
                .sorted(relatedComparator())
                .collect(Collectors.toList());
    }

    static Comparator<RelatedCandidate> relatedComparator() {
        return Comparator
                .comparingInt((RelatedCandidate c) -> rankKey(c.getRank()))
                .thenComparing(RelatedCandidate::getCrowdRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0);
    }

    static Comparator<RecommendationCandidate> candidateComparator() {
        return Comparator
                .comparingInt((RecommendationCandidate c) -> rankKey(c.getRank()))
                .thenComparing(RecommendationCandidate::getCrowdRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0);
    }

    static int rankKey(int rank) {
        return rank <= 0 ? Integer.MAX_VALUE : rank;
    }
}
