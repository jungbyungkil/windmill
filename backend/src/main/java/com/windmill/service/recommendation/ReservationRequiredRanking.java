package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정기휴무 원문에 "예약 시에만 영업" 류 안내가 있는 곳은 뒤로 미룬다(제거는 아님).
 * PopularityRanking보다 먼저 적용되므로, 조회순이 같은 후보끼리만 예약 필수가 뒤로 밀린다.
 */
final class ReservationRequiredRanking {

    private static final String[] KEYWORDS = {
            "예약 시", "예약시", "예약제", "예약 필수", "예약필수", "사전예약", "사전 예약",
            "예약 후 방문", "예약자에 한해", "예약 손님만", "예약 손님에 한해",
    };

    private ReservationRequiredRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates) {
        boolean anyReservationRequired = candidates.stream().anyMatch(ReservationRequiredRanking::requiresReservation);
        if (!anyReservationRequired) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(c -> requiresReservation(c) ? 1 : 0))
                .collect(Collectors.toList());
    }

    private static boolean requiresReservation(RelatedCandidate c) {
        String text = c.getRestDateText();
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
