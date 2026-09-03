package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PopularityRankingTest {

    @Test
    void tourApiRank_comesBeforeCrowdRate() {
        RelatedCandidate quietTop = place("지역 1위", 1, 20.0);
        RelatedCandidate busyLow = place("조회 낮은 혼잡 명소", 8, 95.0);
        RelatedCandidate unknown = place("순위 없음", 0, 70.0);

        List<RelatedCandidate> ranked = PopularityRanking.rank(List.of(busyLow, unknown, quietTop));

        assertEquals(List.of("지역 1위", "조회 낮은 혼잡 명소", "순위 없음"),
                ranked.stream().map(RelatedCandidate::getPlaceName).toList());
    }

    @Test
    void sameRank_breaksTieByHigherCrowdRate() {
        RelatedCandidate a = place("A", 1, 40.0);
        RelatedCandidate b = place("B", 1, 80.0);

        List<RelatedCandidate> ranked = PopularityRanking.rank(List.of(a, b));

        assertEquals("B", ranked.get(0).getPlaceName());
        assertEquals("A", ranked.get(1).getPlaceName());
    }

    @Test
    void emptyOrSingle_leavesListUntouched() {
        List<RelatedCandidate> empty = List.of();
        assertSame(empty, PopularityRanking.rank(empty));

        List<RelatedCandidate> one = List.of(place("혼자", 1, 10.0));
        assertSame(one, PopularityRanking.rank(one));
    }

    private static RelatedCandidate place(String name, int rank, Double crowdRate) {
        return RelatedCandidate.builder()
                .placeName(name)
                .rank(rank)
                .crowdRate(crowdRate)
                .build();
    }
}
