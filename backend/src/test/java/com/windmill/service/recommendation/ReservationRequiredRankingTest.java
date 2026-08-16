package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 예약 없이는 못 들어가는 곳(정기휴무 원문에 "예약 시 영업" 류 안내)이 1순위로 추천되던 문제
 * (2026-08-16 사용자 제보 - 고정핀 근처 맛집 검색 1순위가 "정기휴무: 매주 토요일-일요일 / 법정
 * 공휴일 ※ 단, 예약 시 영업 가능"인 한정식집이었음) 회귀 방지.
 */
class ReservationRequiredRankingTest {

    private static RelatedCandidate place(String name, String restDateText) {
        return RelatedCandidate.builder().placeName(name).restDateText(restDateText).build();
    }

    @Test
    void reservationRequiredPlace_isMovedBehindWalkInPlaces() {
        RelatedCandidate reservationOnly = place("동심 한정식",
                "매주 토요일-일요일 / 법정 공휴일 ※ 단, 예약 시 영업 가능");
        RelatedCandidate walkIn1 = place("원당감자탕", "없음");
        RelatedCandidate walkIn2 = place("유가네닭갈비", null);

        List<RelatedCandidate> ranked = ReservationRequiredRanking.rank(
                List.of(reservationOnly, walkIn1, walkIn2));

        assertEquals(List.of(walkIn1, walkIn2, reservationOnly), ranked);
    }

    @Test
    void noReservationRequiredPlaces_leavesOrderUntouched() {
        RelatedCandidate a = place("원당감자탕", "없음");
        RelatedCandidate b = place("유가네닭갈비", "매주 월요일");

        List<RelatedCandidate> input = List.of(a, b);
        List<RelatedCandidate> ranked = ReservationRequiredRanking.rank(input);

        assertEquals(input, ranked);
    }

    @Test
    void allReservationRequired_keepsRelativeOrder() {
        // 후보 전부가 예약 필요면 서로 순위를 바꿀 이유가 없다(안정 정렬이므로 원래 순서 유지)
        RelatedCandidate a = place("한정식A", "예약 시 영업");
        RelatedCandidate b = place("한정식B", "예약제 운영");

        List<RelatedCandidate> ranked = ReservationRequiredRanking.rank(List.of(a, b));

        assertEquals(List.of(a, b), ranked);
    }

    @Test
    void emptyList_earlyReturnsSameListInstance() {
        List<RelatedCandidate> input = List.of();
        assertSame(input, ReservationRequiredRanking.rank(input));
    }
}
