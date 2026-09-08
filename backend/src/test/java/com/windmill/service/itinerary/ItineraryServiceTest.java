package com.windmill.service.itinerary;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.RegionCode;
import com.windmill.exception.DuplicateActiveItineraryException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
        com.windmill.service.tourapi.TourAttractionService tourAttractionService =
                mock(com.windmill.service.tourapi.TourAttractionService.class);
        com.windmill.service.recommendation.SituationalTagService situationalTagService =
                mock(com.windmill.service.recommendation.SituationalTagService.class);
        when(situationalTagService.ensureInferred(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> com.windmill.domain.PlaceSituationalTags.builder()
                        .contentId(inv.getArgument(0))
                        .indoorYn(false)
                        .rainSensitivity(com.windmill.domain.RainSensitivity.SENSITIVE)
                        .congestionSensitivity(com.windmill.domain.CongestionSensitivity.SENSITIVE)
                        .inferredSource(com.windmill.domain.InferredSource.RULE)
                        .updatedAt(java.time.LocalDateTime.now())
                        .build());
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService,
                routeRecalculationService, tourAttractionService, situationalTagService, new PlanHistoryService());

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

    @Test
    void optimizeRoute_parsesStartTimeAndPassesItToRouteRecalculationService() {
        ItineraryItem item1 = ItineraryItem.builder().id(1L).displayOrder(0).visitDate(TOMORROW).build();
        ItineraryItem item2 = ItineraryItem.builder().id(2L).displayOrder(1).visitDate(TOMORROW).build();
        Itinerary itinerary = Itinerary.builder()
                .id(5L).sessionUuid(SESSION).startDate(TOMORROW)
                .items(new ArrayList<>(List.of(item1, item2)))
                .build();
        when(itineraryRepository.findById(5L)).thenReturn(Optional.of(itinerary));
        when(routeRecalculationService.recalculate(any(), any(), any(), any()))
                .thenReturn(new RouteRecalculationService.Result(List.of(item1, item2), "재계산 완료", 30, true));

        service.optimizeRoute(5L, TOMORROW, null, null, "17:00");

        ArgumentCaptor<LocalTime> captor = ArgumentCaptor.forClass(LocalTime.class);
        verify(routeRecalculationService).recalculate(any(), any(), any(), captor.capture());
        assertEquals(LocalTime.of(17, 0), captor.getValue());
    }

    @Test
    void optimizeRoute_keepsPinnedAnchorOutOfRecalculationAndMergesByTime() {
        ItineraryItem before = ItineraryItem.builder().id(1L).displayOrder(0).visitDate(TOMORROW)
                .scheduledTime("12:00").build();
        ItineraryItem anchor = ItineraryItem.builder().id(2L).displayOrder(1).visitDate(TOMORROW)
                .scheduledTime("12:30").isPinned(true).pinnedReason("19:00 공연").build();
        ItineraryItem after = ItineraryItem.builder().id(3L).displayOrder(2).visitDate(TOMORROW)
                .scheduledTime("13:00").build();
        Itinerary itinerary = Itinerary.builder()
                .id(7L).sessionUuid(SESSION).startDate(TOMORROW)
                .items(new ArrayList<>(List.of(before, anchor, after)))
                .build();
        when(itineraryRepository.findById(7L)).thenReturn(Optional.of(itinerary));
        // 이동 항목만 재계산으로 넘어가고, 스텁은 받은 순서를 그대로 돌려준다
        when(routeRecalculationService.recalculate(any(), any(), any(), any()))
                .thenAnswer(inv -> new RouteRecalculationService.Result(inv.getArgument(0), "재계산 완료", 20, true));

        service.optimizeRoute(7L, TOMORROW, null, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ItineraryItem>> passed = ArgumentCaptor.forClass(List.class);
        verify(routeRecalculationService).recalculate(passed.capture(), any(), any(), any());
        assertEquals(List.of(1L, 3L), passed.getValue().stream().map(ItineraryItem::getId).toList());

        // 고정 앵커(12:30)는 시각순으로 before(12:00)와 after(13:00) 사이에 병합되고 displayOrder도 그 순서
        assertEquals(0, before.getDisplayOrder());
        assertEquals(1, anchor.getDisplayOrder());
        assertEquals(2, after.getDisplayOrder());
        assertEquals("12:30", anchor.getScheduledTime());
    }

    @Test
    void optimizeRoute_allItemsPinned_skipsRecalculationAndKeepsOrder() {
        ItineraryItem p1 = ItineraryItem.builder().id(1L).displayOrder(0).visitDate(TOMORROW)
                .scheduledTime("10:00").isPinned(true).build();
        ItineraryItem p2 = ItineraryItem.builder().id(2L).displayOrder(1).visitDate(TOMORROW)
                .scheduledTime("14:00").isPinned(true).build();
        Itinerary itinerary = Itinerary.builder()
                .id(8L).sessionUuid(SESSION).startDate(TOMORROW)
                .items(new ArrayList<>(List.of(p1, p2)))
                .build();
        when(itineraryRepository.findById(8L)).thenReturn(Optional.of(itinerary));

        service.optimizeRoute(8L, TOMORROW, null, null, null);

        verify(routeRecalculationService, never()).recalculate(any(), any(), any(), any());
        assertEquals("10:00", p1.getScheduledTime());
        assertEquals("14:00", p2.getScheduledTime());
    }

    @Test
    void optimizeRoute_blankStartTime_passesNullOverride() {
        ItineraryItem item1 = ItineraryItem.builder().id(1L).displayOrder(0).visitDate(TOMORROW).build();
        ItineraryItem item2 = ItineraryItem.builder().id(2L).displayOrder(1).visitDate(TOMORROW).build();
        Itinerary itinerary = Itinerary.builder()
                .id(6L).sessionUuid(SESSION).startDate(TOMORROW)
                .items(new ArrayList<>(List.of(item1, item2)))
                .build();
        when(itineraryRepository.findById(6L)).thenReturn(Optional.of(itinerary));
        when(routeRecalculationService.recalculate(any(), any(), any(), any()))
                .thenReturn(new RouteRecalculationService.Result(List.of(item1, item2), "재계산 완료", 30, true));

        service.optimizeRoute(6L, TOMORROW, null, null, null);

        verify(routeRecalculationService).recalculate(any(), any(), any(), isNull());
    }
}
