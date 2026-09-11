package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정기휴무 원문에 "예약 시에만 영업" 류 안내가 있는 곳은 뒤로 미룬다(제거는 아님).
 * PopularityRanking보다 먼저 적용되므로, 조회순이 같은 후보끼리만 예약 필수가 뒤로 밀린다.
 *
 * matchesReservationKeyword는 BadgeAssembler도 재사용한다 - 인기 명소일수록 예약이 필요한 경우가
 * 많아(2026-09-11 사용자 제보) 카드에 "예약 필수" 배지로 노출한다. 키워드 목록을 한 곳에서만
 * 관리해 정렬과 배지 표시가 서로 다른 기준으로 어긋나지 않게 한다.
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
        return matchesReservationKeyword(c.getRestDateText());
    }

    static boolean matchesReservationKeyword(String restDateText) {
        if (restDateText == null || restDateText.isBlank()) {
            return false;
        }
        for (String keyword : KEYWORDS) {
            if (restDateText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
