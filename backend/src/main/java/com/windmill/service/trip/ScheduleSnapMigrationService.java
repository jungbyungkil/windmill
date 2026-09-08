package com.windmill.service.trip;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.repository.ItineraryItemRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.VisitTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일회성 마이그레이션 - 완료된 여행 기록({@link TripRecord} → 연결된 {@link Itinerary}의 항목)의
 * 방문 시각을 신규 스마트 일정과 같은 규칙(30분 단위 올림, 중복·역전은 +30분)으로 일괄 정리한다.
 * 진행 중(TripRecord 없는) 활성 일정은 건드리지 않는다. 원본 백업 없음(데모 전 1회 실행 후 종료).
 *
 * <p>{@code /api/dev/snap-schedule-times} (dry-run) / {@code ?confirm=true} (실제 반영)로 호출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleSnapMigrationService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final TripRecordRepository tripRecordRepository;
    private final ItineraryItemRepository itineraryItemRepository;

    @Transactional
    public Result run(boolean apply) {
        List<TripRecord> records = tripRecordRepository.findAll();
        int recordsProcessed = 0;
        int itemsScanned = 0;
        int itemsChanged = 0;
        int reversalsAfter = 0;

        for (TripRecord record : records) {
            Itinerary itinerary = record.getItinerary();
            if (itinerary == null || itinerary.getItems().isEmpty()) {
                continue;
            }
            recordsProcessed++;

            // (일정, 방문일)별 그룹 - visitDate가 없으면 null 키로 하나에 묶는다(당일치기 전제)
            Map<LocalDate, List<ItineraryItem>> byDate = new LinkedHashMap<>();
            for (ItineraryItem item : itinerary.getItems()) {
                byDate.computeIfAbsent(item.getVisitDate(), k -> new ArrayList<>()).add(item);
            }

            for (List<ItineraryItem> group : byDate.values()) {
                List<ItineraryItem> timed = new ArrayList<>();
                for (ItineraryItem item : group) {
                    if (ClosingTimeGate.parseHhMm(item.getScheduledTime()) != null) {
                        timed.add(item);
                    }
                }
                timed.sort(Comparator.comparing(i -> ClosingTimeGate.parseHhMm(i.getScheduledTime())));
                itemsScanned += timed.size();

                List<LocalTime> raw = new ArrayList<>(timed.size());
                for (ItineraryItem item : timed) {
                    raw.add(ClosingTimeGate.parseHhMm(item.getScheduledTime()));
                }
                List<LocalTime> snapped = VisitTiming.snapSequential(raw);

                LocalTime prev = null;
                for (int i = 0; i < timed.size(); i++) {
                    LocalTime t = snapped.get(i);
                    if (t == null) {
                        continue;
                    }
                    if (prev != null && !t.isAfter(prev)) {
                        reversalsAfter++;
                    }
                    prev = t;
                    String next = t.format(HH_MM);
                    if (!next.equals(timed.get(i).getScheduledTime())) {
                        itemsChanged++;
                        if (apply) {
                            timed.get(i).setScheduledTime(next);
                        }
                    }
                }
            }

            if (apply) {
                itineraryItemRepository.saveAll(itinerary.getItems());
            }
        }

        Result result = new Result(apply, records.size(), recordsProcessed, itemsScanned, itemsChanged, reversalsAfter);
        log.info("[ScheduleSnapMigration] {}", result);
        return result;
    }

    public record Result(boolean applied, int tripRecords, int recordsProcessed,
                         int itemsScanned, int itemsChanged, int reversalsAfter) {
    }
}
