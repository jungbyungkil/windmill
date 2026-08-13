package com.windmill.service.itinerary;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.RegionCode;
import com.windmill.exception.DuplicateActiveItineraryException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 같은 세션·같은 날짜 중복 일정 판정(요구사항 1번) 검증 */
class ItineraryServiceTest {

    private static final String SESSION = "session-uuid";
    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    private ItineraryRepository itineraryRepository;
    private TripRecordRepository tripRecordRepository;
    private RegionCodeService regionCodeService;
    private RouteRecalculationService routeRecalculationService;
    private ItineraryService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        tripRecordRepository = mock(TripRecordRepository.class);
        regionCodeService = mock(RegionCodeService.class);
        routeRecalculationService = mock(RouteRecalculationService.class);
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService, routeRecalculationService);

        when(regionCodeService.find("51210")).thenReturn(Optional.of(RegionCode.builder()
                .sidoName("강원특별자치도").signguName("속초시").signguFullCode("51210")
                .weatherNx("85").weatherNy("125").build()));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateItineraryRequest requestOf(boolean force) {
        CreateItineraryRequest request = new CreateItineraryRequest();
        request.setSignguFullCode("51210");
        request.setStartDate(TOMORROW);
        request.setEndDate(TOMORROW);
        request.setCompanionType(CompanionType.SOLO);
        request.setForce(force);
        return request;
    }

    @Test
    void createThrowsWhenActiveItineraryExistsForSameDate() {
        Itinerary existing = Itinerary.builder().id(1L).sessionUuid(SESSION).startDate(TOMORROW).build();
        when(itineraryRepository.findActiveBySessionUuidAndStartDate(SESSION, TOMORROW))
                .thenReturn(List.of(existing));

        DuplicateActiveItineraryException ex = assertThrows(DuplicateActiveItineraryException.class,
                () -> service.create(SESSION, requestOf(false)));
        assertEquals(existing, ex.getExisting());
        verify(itineraryRepository, never()).save(any());
    }

    @Test
    void createSucceedsWhenNoDuplicateExists() {
        when(itineraryRepository.findActiveBySessionUuidAndStartDate(anyString(), any()))
                .thenReturn(List.of());

        Itinerary created = service.create(SESSION, requestOf(false));

        assertEquals(TOMORROW, created.getStartDate());
        verify(itineraryRepository).save(any(Itinerary.class));
    }

    @Test
    void createWithForceDeletesExistingDuplicatesThenCreates() {
        Itinerary existing = Itinerary.builder().id(1L).sessionUuid(SESSION).startDate(TOMORROW).build();
        when(itineraryRepository.findActiveBySessionUuidAndStartDate(SESSION, TOMORROW))
                .thenReturn(List.of(existing));

        Itinerary created = service.create(SESSION, requestOf(true));

        assertEquals(TOMORROW, created.getStartDate());
        verify(itineraryRepository).deleteAll(List.of(existing));
        verify(itineraryRepository).save(any(Itinerary.class));
    }
}
