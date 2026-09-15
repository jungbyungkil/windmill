package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.dto.BatchPlaceCheckRequest;
import com.windmill.dto.PlaceCheckResult;
import com.windmill.dto.RegionCode;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.repository.ItineraryRepository;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.service.trigger.TriggerScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 여행 바구니 일괄 검증(2026-09-15 핸드오프 브리프 4.1/9.5/10.4) - TriggerDetectionService.detect를
 * 그대로 재사용해 urgent/reasons로 매핑하는지 검증한다.
 */
class PlaceBatchCheckServiceTest {

    private static final Long ITINERARY_ID = 1L;
    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    private ItineraryRepository itineraryRepository;
    private RegionCodeService regionCodeService;
    private TriggerScheduler triggerScheduler;
    private TriggerDetectionService triggerDetectionService;
    private PlaceBatchCheckService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        regionCodeService = mock(RegionCodeService.class);
        triggerScheduler = mock(TriggerScheduler.class);
        triggerDetectionService = mock(TriggerDetectionService.class);
        service = new PlaceBatchCheckService(itineraryRepository, regionCodeService, triggerScheduler,
                triggerDetectionService);

        Itinerary itinerary = Itinerary.builder()
                .id(ITINERARY_ID).signguFullCode("51210").startDate(TOMORROW).build();
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Optional.of(itinerary));
        RegionCode region = RegionCode.builder().signguFullCode("51210").build();
        when(regionCodeService.find("51210")).thenReturn(Optional.of(region));
        when(triggerScheduler.ensureFresh(any())).thenReturn(Mono.just(mock(RegionCondition.class)));
    }

    private static BatchPlaceCheckRequest.Item item(String contentId, String placeName) {
        BatchPlaceCheckRequest.Item i = new BatchPlaceCheckRequest.Item();
        i.setContentId(contentId);
        i.setPlaceName(placeName);
        return i;
    }

    @Test
    void checkBatch_urgentPlace_mapsToUrgentWithReasons() {
        when(triggerDetectionService.detect(any(), any(), eq(TOMORROW)))
                .thenReturn(Mono.just(TriggerResult.builder()
                        .level(TriggerLevel.DANGER)
                        .crowdTrigger(true)
                        .closedDayTrigger(true)
                        .triggerDetails(List.of("속초관광수산시장이 지금 붐벼요"))
                        .build()));

        BatchPlaceCheckRequest request = new BatchPlaceCheckRequest();
        request.setItems(List.of(item("c-1", "속초관광수산시장")));

        List<PlaceCheckResult> results = service.checkBatch(ITINERARY_ID, request).block();

        assertEquals(1, results.size());
        PlaceCheckResult result = results.get(0);
        assertEquals("c-1", result.getContentId());
        assertTrue(result.isUrgent());
        assertTrue(result.isWarning());
        assertEquals(List.of("휴무", "혼잡"), result.getReasons());
        assertEquals("속초관광수산시장이 지금 붐벼요", result.getDetail());
    }

    @Test
    void checkBatch_normalPlace_notUrgentNoReasons() {
        when(triggerDetectionService.detect(any(), any(), eq(TOMORROW)))
                .thenReturn(Mono.just(TriggerResult.builder().level(TriggerLevel.NORMAL).build()));

        BatchPlaceCheckRequest request = new BatchPlaceCheckRequest();
        request.setItems(List.of(item("c-2", "속초해수욕장")));

        List<PlaceCheckResult> results = service.checkBatch(ITINERARY_ID, request).block();

        assertFalse(results.get(0).isUrgent());
        assertFalse(results.get(0).isWarning());
        assertTrue(results.get(0).getReasons().isEmpty());
    }

    @Test
    void checkBatch_detectThrows_fallsBackToNormalInsteadOfFailingWholeBatch() {
        when(triggerDetectionService.detect(any(), any(), eq(TOMORROW)))
                .thenReturn(Mono.error(new RuntimeException("외부 API 실패")));

        BatchPlaceCheckRequest request = new BatchPlaceCheckRequest();
        request.setItems(List.of(item("c-3", "장소3")));

        List<PlaceCheckResult> results = service.checkBatch(ITINERARY_ID, request).block();

        assertEquals(1, results.size());
        assertFalse(results.get(0).isUrgent());
    }

    @Test
    void checkBatch_multipleItems_checksAllConcurrently() {
        when(triggerDetectionService.detect(any(), any(), eq(TOMORROW)))
                .thenReturn(Mono.just(TriggerResult.builder().level(TriggerLevel.NORMAL).build()));

        BatchPlaceCheckRequest request = new BatchPlaceCheckRequest();
        request.setItems(List.of(item("c-1", "A"), item("c-2", "B"), item("c-3", "C")));

        List<PlaceCheckResult> results = service.checkBatch(ITINERARY_ID, request).block();

        assertEquals(3, results.size());
    }
}
