package com.windmill.service.trip;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.repository.ItineraryItemRepository;
import com.windmill.repository.TripRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleSnapMigrationServiceTest {

    private TripRecordRepository tripRecordRepository;
    private ItineraryItemRepository itineraryItemRepository;
    private ScheduleSnapMigrationService service;

    @BeforeEach
    void setUp() {
        tripRecordRepository = mock(TripRecordRepository.class);
        itineraryItemRepository = mock(ItineraryItemRepository.class);
        service = new ScheduleSnapMigrationService(tripRecordRepository, itineraryItemRepository);
    }

    private static ItineraryItem item(String time, LocalDate visitDate) {
        return ItineraryItem.builder().placeName("p-" + time).scheduledTime(time).visitDate(visitDate).build();
    }

    private static TripRecord recordWith(ItineraryItem... items) {
        Itinerary itinerary = Itinerary.builder().build();
        for (ItineraryItem i : items) {
            i.setItinerary(itinerary);
            itinerary.getItems().add(i);
        }
        TripRecord record = TripRecord.builder().build();
        record.setItinerary(itinerary);
        return record;
    }

    @Test
    void dryRun_reportsChangesWithoutWriting() {
        LocalDate day = LocalDate.of(2026, 9, 8);
        TripRecord record = recordWith(item("09:00", day), item("10:20", day), item("10:25", day));
        when(tripRecordRepository.findAll()).thenReturn(List.of(record));

        ScheduleSnapMigrationService.Result result = service.run(false);

        assertEquals(false, result.applied());
        assertEquals(1, result.recordsProcessed());
        assertEquals(3, result.itemsScanned());
        assertEquals(2, result.itemsChanged()); // 10:20→10:30, 10:25→11:00 (중복 +30)
        assertEquals(0, result.reversalsAfter());
        verify(itineraryItemRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        // 원본은 그대로
        assertEquals(List.of("09:00", "10:20", "10:25"),
                record.getItinerary().getItems().stream().map(ItineraryItem::getScheduledTime).toList());
    }

    @Test
    void confirm_snapsSequentiallyAndPersists() {
        LocalDate day = LocalDate.of(2026, 9, 8);
        TripRecord record = recordWith(item("09:00", day), item("10:20", day), item("10:25", day));
        when(tripRecordRepository.findAll()).thenReturn(List.of(record));

        ScheduleSnapMigrationService.Result result = service.run(true);

        assertEquals(true, result.applied());
        assertEquals(List.of("09:00", "10:30", "11:00"),
                record.getItinerary().getItems().stream().map(ItineraryItem::getScheduledTime).toList());
        verify(itineraryItemRepository).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void groupsByVisitDateSeparately() {
        LocalDate d1 = LocalDate.of(2026, 9, 8);
        LocalDate d2 = LocalDate.of(2026, 9, 9);
        TripRecord record = recordWith(
                item("09:10", d1), item("09:40", d1),
                item("09:10", d2));
        when(tripRecordRepository.findAll()).thenReturn(List.of(record));

        service.run(true);

        // 날짜별로 따로 스냅되므로 d2의 09:10은 d1의 값에 영향받지 않는다
        assertEquals(List.of("09:30", "10:00", "09:30"),
                record.getItinerary().getItems().stream().map(ItineraryItem::getScheduledTime).toList());
    }
}
