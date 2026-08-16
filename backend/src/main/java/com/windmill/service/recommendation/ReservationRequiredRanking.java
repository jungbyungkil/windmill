package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 정기휴무 원문에 "예약 시에만 영업" 류 안내가 있는 곳은 뒤로 미룬다(제거는 아님 - AccessibilityRanking과
 * 동일한 원칙). 당일치기처럼 즉흥적으로 들르는 여행에서, 사전 예약 없이는 못 들어가는 곳을 1순위로
 * 추천하면 실제로는 못 가는 추천이 되어버린다(2026-08-16 사용자 제보 - 고정핀 근처 맛집 검색 1순위가
 * "예약 시 영업 가능"인 한정식집이었음. 유가네닭갈비 같은 예약 없이 바로 들어갈 수 있는 곳이 더
 * 적절하다는 피드백). ProximityRanking 바로 앞에 배선해 근접도 버킷 안에서의 타이브레이커로 동작한다.
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
