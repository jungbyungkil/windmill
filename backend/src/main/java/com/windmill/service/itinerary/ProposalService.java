package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.dto.PendingProposal;
import com.windmill.dto.PlanChangeEntry;
import com.windmill.dto.PlanSnapshot;
import com.windmill.exception.ProposalStaleException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.util.KoreaClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 자동 변경 제안(승인제) 생명주기 - [상태 감지] → PENDING → 적용/거절/만료.
 * 2026-09-15 핸드오프 브리프: 동선 최적화·대안 제안이 사용자 확인 없이 자동으로 일정에 적용되던
 * 문제를 "시스템은 제안만 하고, 사용자가 적용을 누르기 전까지 일정은 바뀌지 않는다"로 전환한다.
 *
 * <p>세션당 최대 1건({@code Itinerary.pendingProposal}). PENDING 상태에서는 {@code itinerary_item}의
 * 슬롯 순서를 절대 수정하지 않는다 - accept가 호출됐을 때만 실제 변경이 반영된다.
 *
 * <p>P0 범위: 실제로 확인된 "몰래 바뀜" 버그(App.jsx의 동선 꼬임 감지 useEffect가 이력 없이 조용히
 * optimizeRoute를 호출하던 경로)만 ROUTE trigger로 다룬다. 혼잡·날씨·휴무 대안 교체는 이미 사용자
 * 클릭(PinwheelHero CTA) 후에만 적용되고 이력도 남고 있어 이번 P0에서는 건드리지 않았다 - 같은
 * pending_proposal 스키마로 흡수하려면 후속 작업으로 trigger별 accept 분기를 늘리면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalService {

    static final int EXPIRY_MINUTES = 30;
    static final int COOLDOWN_HOURS = 2;

    private final ItineraryRepository itineraryRepository;
    private final ItineraryService itineraryService;

    /** 대기 중인 제안 조회 - 만료됐으면 조회 시점에 걷어내고 null을 돌려준다. */
    @Transactional
    public PendingProposal getProposal(Long itineraryId) {
        Itinerary itinerary = itineraryService.get(itineraryId);
        return clearIfExpired(itinerary);
    }

    /**
     * 동선 꼬임 감지 시 호출(2분 주기 스케줄러) - 계산만 하고({@link ItineraryService#previewOptimizeRoute}
     * 롤백 트랜잭션) 저장은 실제로 바뀐 게 있을 때 제안으로만 남긴다. 직선거리 폴백이 쓰였으면 근거
     * 신뢰도가 부족해 제안을 만들지 않는다(핸드오프 브리프 5-3).
     *
     * <p>추천 순서가 직전 제안과 똑같으면(사용자가 아직 반응 안 한 사이 스케줄러가 또 돈 경우) 완전히
     * 손대지 않는다 - proposalId를 매번 새로 발급하면, 프론트가 카드를 띄운 사이(최대 90초 폴링
     * 주기) 서버가 그새 새 id로 바꿔버려 정상적으로 [적용]을 눌러도 409(만료)가 뜨는 문제가 있었다.
     *
     * @return 이번 호출로 "새로운" 제안(신규 생성 또는 내용이 바뀐 갱신)이 만들어졌으면 true -
     *         긴급(DANGER) 제안 전용 푸시를 보낼지 판단하는 데 쓰인다(P2).
     */
    @Transactional
    public boolean generateRouteProposalIfNeeded(Long itineraryId) {
        Itinerary itinerary = itineraryService.get(itineraryId);
        clearIfExpired(itinerary);

        PendingProposal existing = itinerary.getPendingProposal();
        if (existing != null && !"ROUTE".equals(existing.getTrigger())) {
            // 다른(우선순위 높은) trigger가 이미 대기 중 - 세션당 1건 규칙, ROUTE로 덮어쓰지 않는다.
            return false;
        }
        if (existing == null && isRouteCooldownActive(itinerary)) {
            return false;
        }

        LocalDate date = itinerary.getStartDate();
        ItineraryService.RouteReorderPreview preview;
        try {
            preview = itineraryService.previewOptimizeRoute(itineraryId, date, null, null, null);
        } catch (Exception e) {
            log.warn("[Proposal] itinerary={} 동선 제안 계산 실패, 생략: {}", itineraryId, e.toString());
            return false;
        }
        if (!preview.roadBased()) {
            log.info("[Proposal] itinerary={} 직선거리 폴백이라 제안 생략", itineraryId);
            return false;
        }
        if (sameOrder(preview.before(), preview.after())) {
            return false;
        }

        List<PlanSnapshot.Stop> after = toStops(preview.after());
        if (existing != null && sameStops(existing.getAfter(), after)) {
            // 추천 순서가 직전 제안과 동일 - proposalId·만료 시각을 그대로 두어 프론트 폴링 중에도
            // 같은 카드가 유지되게 한다.
            return false;
        }

        PendingProposal proposal = PendingProposal.builder()
                .proposalId(UUID.randomUUID().toString())
                .trigger("ROUTE")
                .reason(preview.message() != null ? preview.message() : "동선이 꼬였어요. 순서를 정리하면 이동이 줄어요.")
                .evidence(preview.totalDistanceKm() == null ? List.of() : List.of(
                        PendingProposal.Evidence.builder()
                                .source("KakaoMobility")
                                .label("예상 총 이동거리")
                                .value(String.format("%.1fkm", preview.totalDistanceKm()))
                                .build()))
                .before(toStops(preview.before()))
                .after(after)
                .date(date.toString())
                .createdAt(KoreaClock.nowKstIso())
                .expiresAt(OffsetDateTime.now(KoreaClock.ZONE).plusMinutes(EXPIRY_MINUTES).withNano(0).toString())
                .build();

        itinerary.setPendingProposal(proposal);
        itineraryRepository.save(itinerary);
        log.info("[Proposal] itinerary={} ROUTE 제안 {} proposalId={}",
                itineraryId, existing == null ? "생성" : "갱신", proposal.getProposalId());
        return true;
    }

    /** 제안 적용 - 순서 변경 + 변경 이력(trigger·reason 포함) 기록. */
    @Transactional
    public Itinerary accept(Long itineraryId, String proposalId) {
        Itinerary itinerary = itineraryService.get(itineraryId);
        PendingProposal proposal = requireFresh(itinerary, proposalId);
        itinerary.setPendingProposal(null);
        itineraryRepository.save(itinerary);

        LocalDate date = parseDate(proposal.getDate(), itinerary.getStartDate());
        if ("ROUTE".equals(proposal.getTrigger())) {
            ItineraryService.OptimizeRouteResult result = itineraryService.applyReroute(
                    itineraryId, date, null, null, null, proposal.getReason(),
                    "PROPOSAL_ACCEPTED", toHistoryEvidence(proposal.getEvidence()));
            return result.itinerary();
        }
        throw new IllegalStateException("지원하지 않는 제안 종류: " + proposal.getTrigger());
    }

    /** 제안 카드용 Evidence를 변경 이력용 Evidence로 변환 - 필드 구성이 동일해 값만 옮긴다. */
    private static List<PlanChangeEntry.Evidence> toHistoryEvidence(List<PendingProposal.Evidence> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .map(e -> PlanChangeEntry.Evidence.builder()
                        .source(e.getSource())
                        .label(e.getLabel())
                        .value(e.getValue())
                        .build())
                .toList();
    }

    /** 제안 거절 - 순서 유지, 동일 trigger 재제안 쿨다운 시작. 이력에는 남기지 않는다(FIFO 슬롯 보존). */
    @Transactional
    public void reject(Long itineraryId, String proposalId) {
        Itinerary itinerary = itineraryService.get(itineraryId);
        PendingProposal proposal = requireFresh(itinerary, proposalId);
        itinerary.setPendingProposal(null);
        if ("ROUTE".equals(proposal.getTrigger())) {
            itinerary.setRouteProposalCooldownUntil(
                    OffsetDateTime.now(KoreaClock.ZONE).plusHours(COOLDOWN_HOURS).withNano(0).toString());
        }
        itineraryRepository.save(itinerary);
    }

    private PendingProposal requireFresh(Itinerary itinerary, String proposalId) {
        PendingProposal proposal = clearIfExpired(itinerary);
        if (proposal == null || !Objects.equals(proposal.getProposalId(), proposalId)) {
            throw new ProposalStaleException("제안이 만료됐어요");
        }
        return proposal;
    }

    private PendingProposal clearIfExpired(Itinerary itinerary) {
        PendingProposal proposal = itinerary.getPendingProposal();
        if (proposal == null) {
            return null;
        }
        OffsetDateTime expiresAt = parseKst(proposal.getExpiresAt());
        if (expiresAt != null && OffsetDateTime.now(KoreaClock.ZONE).isAfter(expiresAt)) {
            itinerary.setPendingProposal(null);
            itineraryRepository.save(itinerary);
            return null;
        }
        return proposal;
    }

    private boolean isRouteCooldownActive(Itinerary itinerary) {
        OffsetDateTime cooldownUntil = parseKst(itinerary.getRouteProposalCooldownUntil());
        return cooldownUntil != null && OffsetDateTime.now(KoreaClock.ZONE).isBefore(cooldownUntil);
    }

    private static boolean sameOrder(List<ItineraryService.RouteStopSummary> before,
                                     List<ItineraryService.RouteStopSummary> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            if (!Objects.equals(before.get(i).itemId(), after.get(i).itemId())) {
                return false;
            }
        }
        return true;
    }

    private static List<PlanSnapshot.Stop> toStops(List<ItineraryService.RouteStopSummary> stops) {
        return stops.stream()
                .map(s -> PlanSnapshot.Stop.builder()
                        .placeName(s.placeName())
                        .scheduledTime(s.scheduledTime())
                        .build())
                .toList();
    }

    /** 직전 제안의 after와 이번에 새로 계산한 after가 같은 추천인지 - 같으면 제안을 새로 안 만든다. */
    private static boolean sameStops(List<PlanSnapshot.Stop> a, List<PlanSnapshot.Stop> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i).getPlaceName(), b.get(i).getPlaceName())
                    || !Objects.equals(a.get(i).getScheduledTime(), b.get(i).getScheduledTime())) {
                return false;
            }
        }
        return true;
    }

    private static LocalDate parseDate(String iso, LocalDate fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static OffsetDateTime parseKst(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

}
