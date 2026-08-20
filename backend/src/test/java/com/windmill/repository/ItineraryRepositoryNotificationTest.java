package com.windmill.repository;

import com.windmill.domain.Itinerary;
import com.windmill.domain.TripRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** findActiveTodayForNotification - 알림 대상(오늘 시작 + 아직 여행 마무리 안 한) 판정 검증 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ItineraryRepositoryNotificationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ItineraryRepository itineraryRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private TripRecordRepository tripRecordRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private Itinerary newItinerary(LocalDate startDate) {
        return itineraryRepository.save(Itinerary.builder()
                .sessionUuid("session-" + startDate)
                .signguFullCode("51210")
                .regionDisplayName("강원특별자치도 속초시")
                .startDate(startDate)
                .endDate(startDate)
                .build());
    }

    @Test
    void includesTodayItineraryWithoutTripRecord() {
        Itinerary today = newItinerary(TODAY);

        List<Itinerary> result = itineraryRepository.findActiveTodayForNotification(TODAY);

        assertEquals(1, result.size());
        assertEquals(today.getId(), result.get(0).getId());
    }

    @Test
    void excludesItineraryWithTripRecord() {
        Itinerary finished = newItinerary(TODAY);
        tripRecordRepository.save(TripRecord.builder()
                .sessionUuid(finished.getSessionUuid())
                .itinerary(finished)
                .build());

        List<Itinerary> result = itineraryRepository.findActiveTodayForNotification(TODAY);

        assertEquals(0, result.size());
    }

    @Test
    void excludesItineraryOnDifferentDate() {
        newItinerary(TODAY.plusDays(1));

        List<Itinerary> result = itineraryRepository.findActiveTodayForNotification(TODAY);

        assertEquals(0, result.size());
    }
}
