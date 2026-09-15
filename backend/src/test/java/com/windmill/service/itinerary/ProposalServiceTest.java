package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.dto.PendingProposal;
import com.windmill.dto.PlanChangeEntry;
import com.windmill.exception.ProposalStaleException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.util.KoreaClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인제 제안(2026-09-15 핸드오프 브리프) - 생성 게이트(폴백·무변화·쿨다운·우선순위)와
 * accept/reject의 상태 전이를 검증한다.
 */
class ProposalServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 20);

    private ItineraryRepository itineraryRepository;
    private ItineraryService itineraryService;
    private ProposalService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        itineraryService = mock(ItineraryService.class);
        service = new ProposalService(itineraryRepository, itineraryService);
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Itinerary itinerary() {
        return Itinerary.builder().id(1L).startDate(DATE).build();
    }

    private ItineraryService.RouteStopSummary stop(Long itemId, String name, String time) {
        return new ItineraryService.RouteStopSummary(itemId, name, time);
    }

    @Test
    void generateRouteProposalIfNeeded_createsProposal_whenOrderChangedAndRoadBased() {
        Itinerary itinerary = itinerary();
        when(itineraryService.get(1L)).thenReturn(itinerary);
        when(itineraryService.previewOptimizeRoute(eq(1L), eq(DATE), isNull(), isNull(), isNull()))
                .thenReturn(new ItineraryService.RouteReorderPreview(
                        List.of(stop(1L, "A", "10:00"), stop(2L, "B", "11:00")),
                        List.of(stop(2L, "B", "10:00"), stop(1L, "A", "11:00")),
                        "재계산 완료", 3.2, true));

        boolean fresh = service.generateRouteProposalIfNeeded(1L);

        assertTrue(fresh);
        PendingProposal proposal = itinerary.getPendingProposal();
        assertNotNull(proposal);
        assertEquals("ROUTE", proposal.getTrigger());
        assertEquals("B", proposal.getAfter().get(0).getPlaceName());
        assertEquals(1, proposal.getEvidence().size());
        assertNotNull(proposal.getExpiresAt());
        verify(itineraryRepository).save(itinerary);
    }

    @Test
    void generateRouteProposalIfNeeded_skips_whenFallbackUsed() {
        Itinerary itinerary = itinerary();
        when(itineraryService.get(1L)).thenReturn(itinerary);
        when(itineraryService.previewOptimizeRoute(eq(1L), eq(DATE), isNull(), isNull(), isNull()))
                .thenReturn(new ItineraryService.RouteReorderPreview(
                        List.of(stop(1L, "A", "10:00")),
                        List.of(stop(1L, "A", "11:00")),
                        "직선거리 폴백", 3.2, false));

        boolean fresh = service.generateRouteProposalIfNeeded(1L);

        assertFalse(fresh);
        assertNull(itinerary.getPendingProposal());
    }

    @Test
    void generateRouteProposalIfNeeded_skips_whenOrderUnchanged() {
        Itinerary itinerary = itinerary();
        when(itineraryService.get(1L)).thenReturn(itinerary);
        when(itineraryService.previewOptimizeRoute(eq(1L), eq(DATE), isNull(), isNull(), isNull()))
                .thenReturn(new ItineraryService.RouteReorderPreview(
                        List.of(stop(1L, "A", "10:00")),
                        List.of(stop(1L, "A", "10:00")),
                        null, null, true));

        boolean fresh = service.generateRouteProposalIfNeeded(1L);

        assertFalse(fresh);
        assertNull(itinerary.getPendingProposal());
    }

    @Test
    void generateRouteProposalIfNeeded_skips_whileRouteCooldownActive() {
        Itinerary itinerary = itinerary();
        itinerary.setRouteProposalCooldownUntil(
                OffsetDateTime.now(KoreaClock.ZONE).plusHours(1).toString());
        when(itineraryService.get(1L)).thenReturn(itinerary);

        boolean fresh = service.generateRouteProposalIfNeeded(1L);

        assertFalse(fresh);
        assertNull(itinerary.getPendingProposal());
        verify(itineraryService, never()).previewOptimizeRoute(anyLong(), any(), any(), any(), any());
    }

    /**
     * 직전 제안과 추천 순서가 똑같으면(스케줄러가 또 돌았을 뿐) proposalId를 새로 발급하지 않는다 -
     * 안 그러면 프론트가 카드를 띄운 사이 id가 바뀌어 정상적인 [적용] 클릭이 409(만료)로 튕긴다.
     */
    @Test
    void generateRouteProposalIfNeeded_keepsExistingProposalId_whenRecommendationUnchanged() {
        Itinerary itinerary = itinerary();
        PendingProposal existing = PendingProposal.builder()
                .proposalId("stable-id").trigger("ROUTE")
                .after(List.of(
                        com.windmill.dto.PlanSnapshot.Stop.builder().placeName("B").scheduledTime("10:00").build(),
                        com.windmill.dto.PlanSnapshot.Stop.builder().placeName("A").scheduledTime("11:00").build()))
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).plusMinutes(10).toString())
                .build();
        itinerary.setPendingProposal(existing);
        when(itineraryService.get(1L)).thenReturn(itinerary);
        when(itineraryService.previewOptimizeRoute(eq(1L), eq(DATE), isNull(), isNull(), isNull()))
                .thenReturn(new ItineraryService.RouteReorderPreview(
                        List.of(stop(1L, "A", "10:00"), stop(2L, "B", "11:00")),
                        List.of(stop(2L, "B", "10:00"), stop(1L, "A", "11:00")),
                        "재계산 완료", 3.2, true));

        boolean fresh = service.generateRouteProposalIfNeeded(1L);

        assertFalse(fresh);
        assertEquals("stable-id", itinerary.getPendingProposal().getProposalId());
    }

    @Test
    void generateRouteProposalIfNeeded_doesNotOverwrite_higherPriorityPendingProposal() {
        Itinerary itinerary = itinerary();
        PendingProposal crowdProposal = PendingProposal.builder()
                .proposalId("existing").trigger("CROWD").build();
        itinerary.setPendingProposal(crowdProposal);
        when(itineraryService.get(1L)).thenReturn(itinerary);

        service.generateRouteProposalIfNeeded(1L);

        assertEquals("existing", itinerary.getPendingProposal().getProposalId());
        verify(itineraryService, never()).previewOptimizeRoute(anyLong(), any(), any(), any(), any());
    }

    @Test
    void getProposal_clearsExpiredProposal() {
        Itinerary itinerary = itinerary();
        itinerary.setPendingProposal(PendingProposal.builder()
                .proposalId("p1").trigger("ROUTE")
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).minusMinutes(1).toString())
                .build());
        when(itineraryService.get(1L)).thenReturn(itinerary);

        PendingProposal result = service.getProposal(1L);

        assertNull(result);
        assertNull(itinerary.getPendingProposal());
        verify(itineraryRepository).save(itinerary);
    }

    @Test
    void accept_appliesRerouteWithProposalEvidenceAndClearsProposal() {
        Itinerary itinerary = itinerary();
        String proposalId = UUID.randomUUID().toString();
        itinerary.setPendingProposal(PendingProposal.builder()
                .proposalId(proposalId).trigger("ROUTE").reason("동선이 꼬였어요")
                .date(DATE.toString())
                .evidence(List.of(PendingProposal.Evidence.builder()
                        .source("KakaoMobility").label("예상 총 이동거리").value("3.2km").build()))
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).plusMinutes(10).toString())
                .build());
        when(itineraryService.get(1L)).thenReturn(itinerary);
        Itinerary applied = itinerary();
        when(itineraryService.applyReroute(eq(1L), eq(DATE), isNull(), isNull(), isNull(),
                eq("동선이 꼬였어요"), eq("PROPOSAL_ACCEPTED"), any()))
                .thenReturn(new ItineraryService.OptimizeRouteResult(applied, "적용 완료", 3.2, List.of(), true));

        Itinerary result = service.accept(1L, proposalId);

        assertEquals(applied, result);
        assertNull(itinerary.getPendingProposal());
        verify(itineraryService).applyReroute(eq(1L), eq(DATE), isNull(), isNull(), isNull(),
                eq("동선이 꼬였어요"), eq("PROPOSAL_ACCEPTED"),
                eq(List.of(PlanChangeEntry.Evidence.builder()
                        .source("KakaoMobility").label("예상 총 이동거리").value("3.2km").build())));
    }

    @Test
    void accept_throwsStale_whenProposalIdMismatch() {
        Itinerary itinerary = itinerary();
        itinerary.setPendingProposal(PendingProposal.builder()
                .proposalId("real-id").trigger("ROUTE")
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).plusMinutes(10).toString())
                .build());
        when(itineraryService.get(1L)).thenReturn(itinerary);

        assertThrows(ProposalStaleException.class, () -> service.accept(1L, "wrong-id"));
    }

    @Test
    void accept_throwsStale_whenNoProposalPending() {
        Itinerary itinerary = itinerary();
        when(itineraryService.get(1L)).thenReturn(itinerary);

        assertThrows(ProposalStaleException.class, () -> service.accept(1L, "any-id"));
    }

    @Test
    void reject_clearsProposalAndStartsRouteCooldown() {
        Itinerary itinerary = itinerary();
        String proposalId = UUID.randomUUID().toString();
        itinerary.setPendingProposal(PendingProposal.builder()
                .proposalId(proposalId).trigger("ROUTE")
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).plusMinutes(10).toString())
                .build());
        when(itineraryService.get(1L)).thenReturn(itinerary);

        service.reject(1L, proposalId);

        assertNull(itinerary.getPendingProposal());
        assertNotNull(itinerary.getRouteProposalCooldownUntil());
    }
}
