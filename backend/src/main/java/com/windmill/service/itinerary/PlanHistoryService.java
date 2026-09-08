package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.PlanChangeEntry;
import com.windmill.dto.PlanSnapshot;
import com.windmill.util.KoreaClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 대안 일정 "원본 + 변경 이력" 관리.
 *
 * <ul>
 *   <li><b>원본</b>({@link Itinerary#getOriginalSnapshot()}) - 최초 확정 일정. 첫 변경이 일어날 때
 *       "변경 직전" 상태로 한 번만 채운다(지연 캡처). 이후 불변, FIFO 대상 아님.</li>
 *   <li><b>변경 이력</b>({@link Itinerary#getChangeHistory()}) - 최대 {@value #MAX_HISTORY}개 FIFO.
 *       각 항목은 그 변경 <i>직후</i> 일정 전체 스냅샷을 품는다.</li>
 * </ul>
 *
 * 되돌리기도 하나의 변경으로 이력에 남긴다(triggerType=REVERT).
 */
@Slf4j
@Service
public class PlanHistoryService {

    /** 원본(1) + 변경 이력(9) = 최대 10. 당일치기 세션당 변경은 많아야 5~10건이라 충분. */
    public static final int MAX_HISTORY = 9;

    /**
     * 변경이 이미 {@code itinerary.getItems()}에 반영된 뒤 호출한다.
     *
     * @param beforeSnapshot 변경 직전 스냅샷 - 원본이 아직 없으면 이 값으로 지연 캡처한다.
     *                       (호출자가 변경 전에 {@link #snapshotOf(Itinerary)}로 떠 둬야 한다)
     * @param triggerType    WEATHER | HEAT | CROWD | ROUTE | REVERT | MANUAL
     * @param changedPlaceId 이번 변경으로 새로 담긴 장소 contentId (순수 동선변경·되돌리기면 null)
     */
    public void recordChange(Itinerary itinerary, PlanSnapshot beforeSnapshot,
                             String triggerType, String reason,
                             String changedPlaceId, String changedPlaceName) {
        if (itinerary.getOriginalSnapshot() == null) {
            itinerary.setOriginalSnapshot(beforeSnapshot);
        }
        List<PlanChangeEntry> history = itinerary.getChangeHistory();
        if (history == null) {
            history = new ArrayList<>();
            itinerary.setChangeHistory(history);
        }
        PlanChangeEntry entry = PlanChangeEntry.builder()
                .sequence(nextSequence(history))
                .triggerType(triggerType)
                .reason(reason)
                .changedAt(nowKst())
                .changedPlaceId(changedPlaceId)
                .changedPlaceName(changedPlaceName)
                .snapshot(snapshotOf(itinerary))
                .build();
        history.add(entry);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        log.info("[PlanHistory] 변경 이력 #{} 추가 itineraryId={} trigger={} reason={}",
                entry.getSequence(), itinerary.getId(), triggerType, reason);
    }

    /**
     * 되돌리기 - 대상 스냅샷으로 {@code itinerary_item}을 재구성하고, 되돌리기 자체를 새 이력으로 남긴다.
     *
     * @param targetSequence null이면 원본으로, 아니면 그 번호의 변경 이력으로 되돌린다.
     * @return 되돌린 스냅샷 (대상이 없으면 null - 호출자가 404/400 처리)
     */
    public PlanSnapshot revert(Itinerary itinerary, Integer targetSequence) {
        PlanSnapshot target = resolveTarget(itinerary, targetSequence);
        if (target == null) {
            return null;
        }
        PlanSnapshot before = snapshotOf(itinerary);
        applySnapshot(itinerary, target);
        String reason = targetSequence == null
                ? "원본으로 되돌림"
                : "변경 이력 #" + targetSequence + "(으)로 되돌림";
        recordChange(itinerary, before, "REVERT", reason, null, null);
        return target;
    }

    private PlanSnapshot resolveTarget(Itinerary itinerary, Integer targetSequence) {
        if (targetSequence == null) {
            return itinerary.getOriginalSnapshot();
        }
        List<PlanChangeEntry> history = itinerary.getChangeHistory();
        if (history == null) {
            return null;
        }
        return history.stream()
                .filter(e -> e.getSequence() == targetSequence)
                .map(PlanChangeEntry::getSnapshot)
                .filter(s -> s != null)
                .findFirst()
                .orElse(null);
    }

    /** 현재 {@code itinerary.getItems()} 상태를 스냅샷으로 뜬다. items는 {@code @OrderBy(displayOrder)}. */
    public PlanSnapshot snapshotOf(Itinerary itinerary) {
        List<PlanSnapshot.Stop> stops = new ArrayList<>();
        for (ItineraryItem item : itinerary.getItems()) {
            stops.add(PlanSnapshot.Stop.builder()
                    .contentId(item.getContentId())
                    .contentTypeId(item.getContentTypeId())
                    .placeName(item.getPlaceName())
                    .thumbnailUrl(item.getThumbnailUrl())
                    .mapX(item.getMapX())
                    .mapY(item.getMapY())
                    .scheduledTime(item.getScheduledTime())
                    .visitDate(item.getVisitDate() == null ? null : item.getVisitDate().toString())
                    .displayOrder(item.getDisplayOrder())
                    .category(item.getCategory())
                    .alternate(item.isAlternate())
                    .build());
        }
        return PlanSnapshot.builder()
                .capturedAt(nowKst())
                .stops(stops)
                .build();
    }

    /**
     * 스냅샷으로 {@code itinerary_item}을 통째로 재구성한다. orphanRemoval=true라 리스트를 비우면
     * 기존 행이 삭제되고, 새 {@link ItineraryItem}이 삽입된다.
     *
     * <p>스냅샷은 경량이라 overview·detailFacts·영업시간 같은 부가 스냅샷 필드는 복원되지 않는다
     * (되돌리기는 "그때 그 장소·순서·시각"을 되살리는 것이 목적).
     */
    public void applySnapshot(Itinerary itinerary, PlanSnapshot snapshot) {
        List<ItineraryItem> items = itinerary.getItems();
        items.clear();
        if (snapshot == null || snapshot.getStops() == null) {
            return;
        }
        int order = 0;
        for (PlanSnapshot.Stop stop : snapshot.getStops()) {
            if (stop.getContentId() == null || stop.getPlaceName() == null) {
                continue;
            }
            ItineraryItem item = ItineraryItem.builder()
                    .itinerary(itinerary)
                    .contentId(stop.getContentId())
                    .contentTypeId(stop.getContentTypeId())
                    .placeName(stop.getPlaceName())
                    .thumbnailUrl(stop.getThumbnailUrl())
                    .mapX(stop.getMapX())
                    .mapY(stop.getMapY())
                    .scheduledTime(stop.getScheduledTime())
                    .visitDate(parseDate(stop.getVisitDate(), itinerary.getStartDate()))
                    .category(stop.getCategory())
                    .isAlternate(stop.isAlternate())
                    .displayOrder(stop.getDisplayOrder() != null ? stop.getDisplayOrder() : order)
                    .build();
            items.add(item);
            order++;
        }
    }

    private static int nextSequence(List<PlanChangeEntry> history) {
        int max = 0;
        for (PlanChangeEntry e : history) {
            if (e.getSequence() > max) {
                max = e.getSequence();
            }
        }
        return max + 1;
    }

    private static java.time.LocalDate parseDate(String iso, java.time.LocalDate fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return java.time.LocalDate.parse(iso.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nowKst() {
        return OffsetDateTime.now(KoreaClock.ZONE).withNano(0).toString();
    }
}
