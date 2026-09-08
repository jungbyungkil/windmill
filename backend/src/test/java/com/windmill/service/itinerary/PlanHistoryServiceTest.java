package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.PlanChangeEntry;
import com.windmill.dto.PlanSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanHistoryServiceTest {

    private final PlanHistoryService service = new PlanHistoryService();

    private Itinerary itineraryWith(String... placeNames) {
        Itinerary it = Itinerary.builder()
                .id(1L)
                .startDate(LocalDate.of(2026, 9, 9))
                .items(new ArrayList<>())
                .changeHistory(new ArrayList<>())
                .build();
        long id = 1;
        int order = 0;
        for (String name : placeNames) {
            it.getItems().add(ItineraryItem.builder()
                    .id(id++)
                    .itinerary(it)
                    .contentId("C" + name)
                    .placeName(name)
                    .scheduledTime(String.format("%02d:00", 9 + order))
                    .displayOrder(order++)
                    .build());
        }
        return it;
    }

    @Test
    void recordChange_capturesOriginalOnlyOnce() {
        Itinerary it = itineraryWith("A", "B", "C");
        PlanSnapshot before1 = service.snapshotOf(it);

        // 첫 변경: B 제거
        it.getItems().removeIf(i -> i.getPlaceName().equals("B"));
        service.recordChange(it, before1, "WEATHER", "비 예보", "CX", "X");

        assertNotNull(it.getOriginalSnapshot(), "첫 변경 때 원본이 캡처돼야 함");
        assertEquals(3, it.getOriginalSnapshot().getStops().size(), "원본은 변경 직전(A,B,C)");
        assertEquals(1, it.getChangeHistory().size());
        assertEquals(1, it.getChangeHistory().get(0).getSequence());

        // 두 번째 변경
        PlanSnapshot before2 = service.snapshotOf(it);
        service.recordChange(it, before2, "CROWD", "혼잡", "CY", "Y");
        assertEquals(3, it.getOriginalSnapshot().getStops().size(), "원본은 두 번째 변경 때 덮이지 않음");
        assertEquals(2, it.getChangeHistory().size());
        assertEquals(2, it.getChangeHistory().get(1).getSequence());
    }

    @Test
    void changeHistory_isFifoNineAndSequenceDoesNotReuse() {
        Itinerary it = itineraryWith("A", "B");
        for (int i = 0; i < 12; i++) {
            service.recordChange(it, service.snapshotOf(it), "MANUAL", "변경" + i, null, null);
        }
        List<PlanChangeEntry> history = it.getChangeHistory();
        assertEquals(9, history.size(), "FIFO 9개로 유지");
        assertEquals(4, history.get(0).getSequence(), "#1~#3은 밀려나고 #4부터 남음");
        assertEquals(12, history.get(8).getSequence(), "누적 번호는 계속 증가(재사용 안 함)");
    }

    @Test
    void revert_toOriginal_restoresItemsAndLogsRevertEntry() {
        Itinerary it = itineraryWith("A", "B", "C");
        service.recordChange(it, service.snapshotOf(it), "WEATHER", "비", "CX", "X");
        it.getItems().removeIf(i -> i.getPlaceName().equals("A"));
        service.recordChange(it, service.snapshotOf(it), "CROWD", "혼잡", "CY", "Y");
        int historyBefore = it.getChangeHistory().size();

        PlanSnapshot reverted = service.revert(it, null);

        assertNotNull(reverted);
        assertEquals(List.of("A", "B", "C"),
                it.getItems().stream().map(ItineraryItem::getPlaceName).toList(),
                "원본 장소 구성으로 복원");
        assertEquals(historyBefore + 1, it.getChangeHistory().size(), "되돌리기도 이력에 남음");
        PlanChangeEntry last = it.getChangeHistory().get(it.getChangeHistory().size() - 1);
        assertEquals("REVERT", last.getTriggerType());
        assertTrue(last.getReason().contains("원본"));
    }

    @Test
    void revert_toSpecificSequence() {
        Itinerary it = itineraryWith("A", "B", "C");
        service.recordChange(it, service.snapshotOf(it), "WEATHER", "비", "CX", "X"); // seq 1, snapshot = A,B,C
        it.getItems().removeIf(i -> i.getPlaceName().equals("C"));
        service.recordChange(it, service.snapshotOf(it), "CROWD", "혼잡", "CY", "Y"); // seq 2, snapshot = A,B

        service.revert(it, 1);

        assertEquals(List.of("A", "B", "C"),
                it.getItems().stream().map(ItineraryItem::getPlaceName).toList());
        assertEquals(3, it.getChangeHistory().size());
        assertEquals("REVERT", it.getChangeHistory().get(2).getTriggerType());
        assertTrue(it.getChangeHistory().get(2).getReason().contains("#1"));
    }

    @Test
    void revert_withNoOriginal_returnsNull() {
        Itinerary it = itineraryWith("A", "B");
        assertNull(service.revert(it, null));
        assertNull(service.revert(it, 5));
        assertTrue(it.getChangeHistory().isEmpty());
    }

    @Test
    void snapshotRoundTrip_preservesOrderTimeCategory() {
        Itinerary it = itineraryWith("A", "B", "C");
        it.getItems().get(1).setCategory("점심");
        it.getItems().get(1).setAlternate(true);
        PlanSnapshot snap = service.snapshotOf(it);

        Itinerary target = itineraryWith("Z"); // 다른 구성
        service.applySnapshot(target, snap);

        assertEquals(List.of("A", "B", "C"),
                target.getItems().stream().map(ItineraryItem::getPlaceName).toList());
        assertEquals("점심", target.getItems().get(1).getCategory());
        assertTrue(target.getItems().get(1).isAlternate());
        assertEquals("10:00", target.getItems().get(1).getScheduledTime());
        assertEquals(0, target.getItems().get(0).getDisplayOrder());
    }
}
