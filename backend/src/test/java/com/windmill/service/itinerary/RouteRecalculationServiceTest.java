package com.windmill.service.itinerary;

import com.windmill.domain.ItineraryItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 표준 4단계 일정을 optimizeRoute로 재계산할 때, 관광·맛집 풀이 별도 API 호출로 채워지다 보니
 * 맛집 태그 장소끼리 TSP 순서상 붙어버리는 경우(맛집→맛집→관광→관광) 점심/저녁 앵커가 서로 붙어
 * 배정되고 관광 일정이 저녁 시간대로 몰리던 버그(2026-08-16 사용자 제보, 8/17 예정 일정인데
 * 12:00/18:00/19:07/20:00로 저녁에 몰려 나옴) 회귀 방지.
 */
class RouteRecalculationServiceTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private final RouteRecalculationService service = new RouteRecalculationService(null);

    private static ItineraryItem place(long id, String placeName, boolean meal) {
        return ItineraryItem.builder()
                .id(id)
                .placeName(placeName)
                .visitDate(TOMORROW)
                .tags(meal ? List.of("#맛집") : List.of("#자연"))
                .build();
    }

    private static ItineraryItem placeWithClose(long id, String placeName, String closeTime) {
        return ItineraryItem.builder()
                .id(id)
                .placeName(placeName)
                .visitDate(TOMORROW)
                .tags(List.of("#자연"))
                .closeTime(closeTime)
                .build();
    }

    @Test
    void declumpAdjacentMeals_splitsAdjacentMealPairWithNearestLaterNonMeal() {
        ItineraryItem food1 = place(1, "새봄떡국국수", true);
        ItineraryItem food2 = place(2, "한암동 정동점", true);
        ItineraryItem attr1 = place(3, "농업박물관", false);
        ItineraryItem attr2 = place(4, "국도발전전시관", false);

        List<ItineraryItem> result = RouteRecalculationService.declumpAdjacentMeals(
                List.of(food1, food2, attr1, attr2));

        assertEquals(List.of(food1, attr1, food2, attr2), result);
    }

    @Test
    void declumpAdjacentMeals_leavesAlreadySpreadOrderUntouched() {
        ItineraryItem food1 = place(1, "새봄떡국국수", true);
        ItineraryItem attr1 = place(2, "농업박물관", false);
        ItineraryItem food2 = place(3, "한암동 정동점", true);
        ItineraryItem attr2 = place(4, "국도발전전시관", false);

        List<ItineraryItem> result = RouteRecalculationService.declumpAdjacentMeals(
                List.of(food1, attr1, food2, attr2));

        assertEquals(List.of(food1, attr1, food2, attr2), result);
    }

    @Test
    void assignSchedule_futureDate_withoutDeclump_stillSqueezesIntoEvening() {
        // 회귀 재현: declump 없이 TSP가 준 원래 순서(맛집→맛집→관광→관광) 그대로 시간표를 잡으면
        // 두 관광 일정이 전부 저녁 시간대(18:20 이후)로 밀린다 - declump가 필요한 이유를 문서화.
        List<ItineraryItem> clumped = List.of(
                place(1, "새봄떡국국수", true),
                place(2, "한암동 정동점", true),
                place(3, "농업박물관", false),
                place(4, "국도발전전시관", false));

        service.assignSchedule(clumped, null, null, null);

        assertEquals("11:00", clumped.get(0).getScheduledTime());
        assertEquals("17:00", clumped.get(1).getScheduledTime());
        assertEquals("18:20", clumped.get(2).getScheduledTime());
        assertEquals("19:55", clumped.get(3).getScheduledTime());
    }

    @Test
    void assignSchedule_futureDate_afterDeclump_spreadsAcrossFullDay() {
        List<ItineraryItem> clumped = List.of(
                place(1, "새봄떡국국수", true),
                place(2, "한암동 정동점", true),
                place(3, "농업박물관", false),
                place(4, "국도발전전시관", false));

        List<ItineraryItem> declumped = RouteRecalculationService.declumpAdjacentMeals(clumped);
        service.assignSchedule(declumped, null, null, null);

        assertEquals("11:00", declumped.get(0).getScheduledTime()); // 새봄떡국국수 (점심)
        assertEquals("12:20", declumped.get(1).getScheduledTime()); // 농업박물관 (오후)
        assertEquals("17:00", declumped.get(2).getScheduledTime()); // 한암동 정동점 (저녁)
        assertEquals("18:20", declumped.get(3).getScheduledTime()); // 국도발전전시관
    }

    @Test
    void assignSchedule_mealArrivingNaturallyInsideWindow_isNotNudged() {
        // 자연스러운 도착 시각이 이미 점심시간대(11:00~14:00) 안이면 정각(12:00)으로 강제하지 않고
        // 그대로 둔다 - 옛 로직(무조건 12:00 점프)과 달리 자연스러운 흐름을 존중한다.
        ItineraryItem attr1 = place(1, "농업박물관", false);
        ItineraryItem attr2 = place(2, "국도발전전시관", false);
        ItineraryItem food = place(3, "새봄떡국국수", true);

        service.assignSchedule(List.of(attr1, attr2, food), null, null, null);

        assertEquals("09:00", attr1.getScheduledTime());
        assertEquals("10:35", attr2.getScheduledTime());
        // 10:35 + 75 + 20 = 12:10 - 점심 창(11:00~14:00) 안이라 자연스러운 도착 시각 그대로 유지
        assertEquals("12:10", food.getScheduledTime());
    }

    @Test
    void repairClosingTimeConflicts_movesLateClosingPlaceToLatestFeasibleEarlierSlot() {
        // TSP가 순수 거리만 보고 마감 이른 곳(우표박물관, 16:50)을 하루 맨 끝에 놔둔 상황을 재현
        // (2026-08-16 사용자 제보 - 동선 재계산을 돌리면 방금 마감시간 맞춰 끼워 넣은 자리가
        // 다시 흐트러지던 문제).
        ItineraryItem food1 = place(1, "새봄떡국국수", true);
        ItineraryItem attr1 = place(2, "농업박물관", false);
        ItineraryItem food2 = place(3, "한암동 정동점", true);
        ItineraryItem attr2 = place(4, "국도발전전시관", false);
        ItineraryItem stampMuseum = placeWithClose(5, "우표박물관", "16:50");

        List<ItineraryItem> repaired = service.repairClosingTimeConflicts(
                List.of(food1, attr1, food2, attr2, stampMuseum), null, null);

        assertEquals(List.of(food1, attr1, stampMuseum, food2, attr2), repaired);

        service.assignSchedule(repaired, null, null, null);
        assertEquals("11:00", food1.getScheduledTime());
        assertEquals("12:20", attr1.getScheduledTime());
        assertEquals("13:55", stampMuseum.getScheduledTime()); // 마감(16:50-60=15:50) 안에 도착
        assertEquals("17:00", food2.getScheduledTime());
        assertEquals("18:20", attr2.getScheduledTime());
    }

    @Test
    void repairClosingTimeConflicts_noViolation_leavesOrderUntouched() {
        ItineraryItem attr1 = place(1, "농업박물관", false);
        ItineraryItem stampMuseum = placeWithClose(2, "우표박물관", "16:50");

        List<ItineraryItem> ordered = List.of(attr1, stampMuseum);
        List<ItineraryItem> repaired = service.repairClosingTimeConflicts(ordered, null, null);

        assertEquals(ordered, repaired);
    }

    @Test
    void assignSchedule_withOverrideStartTime_usesItInsteadOfAutoDayStart() {
        // 사용자가 "동선 재계산" 시 첫 장소 도착 시각을 직접 지정(예: 17:00)하면 미래/오늘 자동
        // 판정(DAY_START=09:00 등)을 건너뛰고 그 시각부터 시작, 나머지는 자연스럽게 이어 붙는다.
        ItineraryItem attr1 = place(1, "서부감자국", false);
        ItineraryItem attr2 = place(2, "서울기록원", false);

        service.assignSchedule(List.of(attr1, attr2), null, null, null, LocalTime.of(17, 0));

        assertEquals("17:00", attr1.getScheduledTime());
        // 17:00 + 75(체류) + 20(이동) = 18:35
        assertEquals("18:35", attr2.getScheduledTime());
    }

    @Test
    void assignSchedule_noOverride_fallsBackToAutoDayStart() {
        ItineraryItem attr1 = place(1, "농업박물관", false);

        service.assignSchedule(List.of(attr1), null, null, null, null);

        assertEquals("09:00", attr1.getScheduledTime()); // 미래 날짜라 기본 09:00
    }

    @Test
    void repairClosingTimeConflicts_noFeasibleSlotAnywhere_givesUpWithoutReordering() {
        // 하루 시작(09:00)+이동 20분=09:20 도착조차 마감(09:00-60=08:00... 음수 방지로 08:00보다도
        // 이른)을 넘기는 극단적으로 이른 마감 - 어디로 옮겨도 못 맞추므로 순서를 그대로 둔다.
        ItineraryItem attr1 = place(1, "농업박물관", false);
        ItineraryItem tooEarly = placeWithClose(2, "너무일찍닫는곳", "09:10");

        List<ItineraryItem> ordered = List.of(attr1, tooEarly);
        List<ItineraryItem> repaired = service.repairClosingTimeConflicts(ordered, null, null);

        assertEquals(ordered, repaired);
    }
}
