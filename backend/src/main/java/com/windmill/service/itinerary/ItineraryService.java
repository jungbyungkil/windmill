package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.PlaceSituationalTags;
import com.windmill.domain.TripRecord;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.ApplyAlternativeRequest;
import com.windmill.dto.ApplySuggestedRouteRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.PlanSnapshot;
import com.windmill.dto.ItineraryItemResponse;
import com.windmill.dto.ItineraryListItemResponse;
import com.windmill.dto.ItineraryResponse;
import com.windmill.dto.ItineraryStatus;
import com.windmill.dto.RegionCode;
import com.windmill.dto.SharedItineraryResponse;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.exception.ClosingTimeInfeasibleException;
import com.windmill.exception.DuplicateActiveItineraryException;
import com.windmill.exception.TimeSlotConflictException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.service.recommendation.SituationalTagService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.GeoUtils;
import com.windmill.util.KoreaClock;
import com.windmill.util.PlaceTagSanitizer;
import com.windmill.util.TimeConflictGate;
import com.windmill.util.VisitOrderOptimizer;
import com.windmill.util.VisitTiming;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 일정 CRUD - 회원가입 없이 클라이언트가 매 요청 헤더(X-Session-Id)로 보내는 익명 UUID로만 스코핑한다.
 * JPA는 블로킹이므로 컨트롤러에서 별도 스레드(boundedElastic)로 감싸 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ItineraryRepository itineraryRepository;
    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;
    private final RouteRecalculationService routeRecalculationService;
    private final TourAttractionService tourAttractionService;
    private final SituationalTagService situationalTagService;
    private final PlanHistoryService planHistoryService;

    @Transactional
    public Itinerary create(String sessionUuid, CreateItineraryRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null
                || !request.getStartDate().equals(request.getEndDate())) {
            throw new IllegalArgumentException("당일치기만 가능합니다. 여행 날짜는 하루만 선택해 주세요.");
        }
        if (request.getStartDate().isBefore(KoreaClock.today())) {
            throw new IllegalArgumentException("여행일은 오늘 이후여야 합니다.");
        }
        RegionCode region = regionCodeService.find(request.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + request.getSignguFullCode()));

        List<Itinerary> duplicates = itineraryRepository.findActiveBySessionUuidAndStartDate(
                sessionUuid, request.getStartDate());
        if (!duplicates.isEmpty()) {
            if (!request.isForce()) {
                throw new DuplicateActiveItineraryException(duplicates.get(0));
            }
            itineraryRepository.deleteAll(duplicates);
        }

        Itinerary itinerary = Itinerary.builder()
                .sessionUuid(sessionUuid)
                .signguFullCode(region.getSignguFullCode())
                .regionDisplayName(region.getSidoName() + " " + region.getSignguName())
                .weatherNx(region.getWeatherNx())
                .weatherNy(region.getWeatherNy())
                .startDate(request.getStartDate())
                .endDate(request.getStartDate()) // 당일치기: 종료일 = 시작일
                .companionType(request.getCompanionType())
                .withPet(request.isWithPet())
                .strollerFriendly(request.isStrollerFriendly())
                .accessibleFriendly(request.isAccessibleFriendly())
                .adultAgeGroup(request.getAdultAgeGroup())
                .childAges(request.getChildAges() == null ? new ArrayList<>() : new ArrayList<>(request.getChildAges()))
                .partySize(request.getPartySize() != null ? request.getPartySize() : 1)
                .build();
        return itineraryRepository.save(itinerary);
    }

    /**
     * 추천 여행 기록의 원본 일정을 그대로 복제해 새 당일치기를 만든다.
     * 지역·동행·장소·시간·태그는 원본을 유지하고, 여행일만 startDate로 바꾼다.
     */
    @Transactional
    public Itinerary createFromTripRecord(String sessionUuid, Long tripRecordId, LocalDate startDate) {
        if (startDate == null) {
            throw new IllegalArgumentException("여행 날짜를 선택해 주세요.");
        }
        if (startDate.isBefore(KoreaClock.today())) {
            throw new IllegalArgumentException("여행일은 오늘 이후여야 합니다.");
        }
        TripRecord record = tripRecordRepository.findById(tripRecordId)
                .orElseThrow(() -> new EntityNotFoundException("여행 기록을 찾을 수 없습니다: " + tripRecordId));
        Itinerary source = record.getItinerary();
        if (source == null) {
            throw new IllegalArgumentException("이 기록에는 복제할 일정이 없습니다.");
        }
        if (source.getItems() == null || source.getItems().isEmpty()) {
            throw new IllegalArgumentException("이 기록에는 장소가 없어 그대로 시작할 수 없습니다.");
        }

        record.setClickCount(record.getClickCount() + 1);

        Itinerary clone = Itinerary.builder()
                .sessionUuid(sessionUuid)
                .signguFullCode(source.getSignguFullCode())
                .regionDisplayName(source.getRegionDisplayName())
                .weatherNx(source.getWeatherNx())
                .weatherNy(source.getWeatherNy())
                .startDate(startDate)
                .endDate(startDate)
                .companionType(source.getCompanionType())
                .withPet(source.isWithPet())
                .strollerFriendly(source.isStrollerFriendly())
                .accessibleFriendly(source.isAccessibleFriendly())
                .adultAgeGroup(source.getAdultAgeGroup())
                .childAges(source.getChildAges() == null ? new ArrayList<>() : new ArrayList<>(source.getChildAges()))
                .partySize(source.getPartySize())
                .build();

        List<ItineraryItem> ordered = source.getItems().stream()
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        int order = 0;
        for (ItineraryItem src : ordered) {
            List<String> tags = src.getTags() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(src.getTags());
            ItineraryItem item = ItineraryItem.builder()
                    .itinerary(clone)
                    .contentId(src.getContentId())
                    .contentTypeId(src.getContentTypeId())
                    .placeName(src.getPlaceName())
                    .thumbnailUrl(src.getThumbnailUrl())
                    .scheduledTime(src.getScheduledTime())
                    .tags(tags)
                    .crowdRate(src.getCrowdRate())
                    .displayOrder(order++)
                    .visitDate(startDate)
                    .addr1(src.getAddr1())
                    .tel(src.getTel())
                    .useFeeText(src.getUseFeeText())
                    .isFree(src.getIsFree())
                    .estimatedCostPerPerson(src.getEstimatedCostPerPerson())
                    .restDateText(src.getRestDateText())
                    .closeTime(src.getCloseTime())
                    .useTimeText(src.getUseTimeText())
                    .homepageUrl(src.getHomepageUrl())
                    .overview(src.getOverview())
                    .detailFacts(src.getDetailFacts() == null ? null : new ArrayList<>(src.getDetailFacts()))
                    .indoorYn(src.getIndoorYn())
                    .strollerFriendly(src.getStrollerFriendly())
                    .accessibleFriendly(src.isAccessibleFriendly())
                    .category(src.getCategory())
                    .isAlternate(src.isAlternate())
                    .mapX(src.getMapX())
                    .mapY(src.getMapY())
                    .isPinned(false)
                    .build();
            clone.getItems().add(item);
        }
        return itineraryRepository.save(clone);
    }

    @Transactional(readOnly = true)
    public Itinerary get(Long itineraryId) {
        return itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new EntityNotFoundException("일정을 찾을 수 없습니다: " + itineraryId));
    }

    @Transactional(readOnly = true)
    public List<Itinerary> findBySession(String sessionUuid) {
        return itineraryRepository.findBySessionUuid(sessionUuid);
    }

    @Transactional(readOnly = true)
    public ItineraryStatus statusOf(Itinerary itinerary) {
        return ItineraryStatus.of(itinerary.getStartDate(),
                tripRecordRepository.existsByItinerary_Id(itinerary.getId()));
    }

    /** 일정 응답에 상황 태그 테이블 값을 붙인다(수동 보정이 스냅샷보다 우선). */
    @Transactional(readOnly = true)
    public ItineraryResponse toEnrichedResponse(Itinerary itinerary) {
        ItineraryResponse response = ItineraryResponse.from(itinerary, statusOf(itinerary));
        List<String> ids = response.getItems().stream()
                .map(ItineraryItemResponse::getContentId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, PlaceSituationalTags> byId = situationalTagService.findByContentIds(ids);
        for (ItineraryItemResponse item : response.getItems()) {
            PlaceSituationalTags tags = byId.get(item.getContentId());
            if (tags == null) {
                continue;
            }
            item.setIndoor(tags.getIndoorYn());
            item.setRainSensitivity(tags.getRainSensitivity());
            item.setCongestionSensitivity(tags.getCongestionSensitivity());
            item.setInferredSource(tags.getInferredSource());
        }
        return response;
    }

    /**
     * GNB "내 여행 관리" 전체 목록 - ACTIVE(진행 중)/ENDED(종료) 통합, 최신순(여행일→생성일) 정렬.
     * statusFilter가 있으면 그 상태만, limit으로 최근 N건만 반환(계속 쌓이는 구조라 무제한 조회 방지).
     */
    @Transactional(readOnly = true)
    public List<ItineraryListItemResponse> listAll(String sessionUuid, ItineraryStatus statusFilter, int limit) {
        Map<Long, TripRecord> recordByItineraryId = tripRecordRepository.findBySessionUuid(sessionUuid).stream()
                .filter(t -> t.getItinerary() != null)
                .collect(Collectors.toMap(t -> t.getItinerary().getId(), t -> t, (a, b) -> a));

        return itineraryRepository.findBySessionUuid(sessionUuid).stream()
                .sorted(Comparator
                        .comparing(Itinerary::getStartDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Itinerary::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(i -> {
                    TripRecord record = recordByItineraryId.get(i.getId());
                    return ItineraryListItemResponse.from(i,
                            ItineraryStatus.of(i.getStartDate(), record != null),
                            record);
                })
                .filter(r -> statusFilter == null || r.getStatus() == statusFilter)
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    /** 일정 삭제 - 정리(중복 정리 등) 용도. 마무리 기록(TripRecord/VisitFeedback)이 있으면 먼저 지워 FK 제약을 피한다. */
    @Transactional
    public void delete(Long itineraryId) {
        Itinerary itinerary = get(itineraryId);
        List<TripRecord> records = tripRecordRepository.findByItinerary_Id(itineraryId);
        if (!records.isEmpty()) {
            tripRecordRepository.deleteAll(records);
        }
        itineraryRepository.delete(itinerary);
    }

    /** 여행 마무리(TripRecord) 전이면서 오늘(KST) 이후인 당일치기만 - 메인 "진행 중인 여행" 목록 */
    @Transactional(readOnly = true)
    public List<Itinerary> findOngoingDayTrips(String sessionUuid) {
        return itineraryRepository.findOngoingDayTripsBySession(sessionUuid, KoreaClock.today());
    }

    /**
     * 일정에 담기. 시각이 명시된 추가는 같은 날 겹침(TIME_OVERLAP)을 마감(CLOSING_TIME_INFEASIBLE)보다
     * 먼저 검사해 저장을 막는다. 시각이 없으면 가능하면 마감을 안 넘기는 자리에 끼워 넣고,
     * 자리가 없어도 거절하지 않고 하루 끝에 이어붙인다(시간 미지정).
     */
    @Transactional
    public Itinerary addItem(Long itineraryId, AddItineraryItemRequest request) {
        Itinerary itinerary = get(itineraryId);
        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : itinerary.getStartDate();
        if (alreadyHasPlace(itinerary, request.getContentId(), visitDate)) {
            log.info("[addItem] 이미 담긴 장소 재추가 무시 itineraryId={} contentId={} place={}",
                    itineraryId, request.getContentId(), request.getPlaceName());
            return itinerary;
        }
        // 지도 검색·대안 카드에서 담는 경우만 변경 이력에 남긴다(최초 일정 생성 단계 대량 추가는 제외).
        boolean logHistory = Boolean.TRUE.equals(request.getLogHistory());
        PlanSnapshot beforeSnapshot = logHistory ? planHistoryService.snapshotOf(itinerary) : null;
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                request.getTags(), request.getContentTypeId(), request.getPlaceName(), request.getCategory());
        PlaceSituationalTags situational = situationalTagService.ensureInferred(
                request.getContentId(), request.getContentTypeId(), request.getCat3(),
                request.getPlaceName(), request.getOverview());
        if (Boolean.TRUE.equals(situational.getIndoorYn()) && !tags.contains("#실내")) {
            tags = new ArrayList<>(tags);
            tags.add(0, "#실내");
        }

        String scheduledTime = request.getScheduledTime();
        List<ItineraryItem> dayItems = null;
        Integer insertBeforeDayIndex = null;
        LocalTime close = VisitTiming.resolveCloseTime(request);
        int packingStay = VisitTiming.packingStayMinutes(request);
        int closeBuffer = VisitTiming.closeBufferMinutes(
                request.getCloseTime(), request.getUseTimeText(), request.getDetailFacts());

        if (scheduledTime != null) {
            boolean skipClosing = Boolean.TRUE.equals(request.getAcknowledgeHoursWarning());
            assertScheduleFeasible(itinerary, visitDate, scheduledTime, null, close, packingStay, closeBuffer,
                    skipClosing);
        } else {
            LocalTime endArrival = estimateArrivalTime(itinerary, visitDate, request.getMapX(), request.getMapY());
            ClosingTimeGate.CheckResult endCheck = ClosingTimeGate.check(close, endArrival, closeBuffer);
            if (!endCheck.allowed()) {
                // 하루 맨 끝에는 마감 때문에 못 붙는다 - 마감을 안 넘기는 자리 중 가장 늦은(=기존
                // 앞쪽 일정을 최대한 안 건드리는) 위치를 찾아 그 자리에 끼워 넣는다. 못 찾으면
                // 그냥 하루 끝에 이어붙인다 - scheduledTime 미배정 상태로 남아 기존 "시간 미지정 항목"과
                // 동일하게 처리됨.
                dayItems = dayItemsSorted(itinerary, visitDate);
                InsertionPlan plan = dayItems.isEmpty() ? null
                        : findFeasibleInsertion(dayItems, visitDate, request.getMapX(), request.getMapY(),
                                close, closeBuffer);
                if (plan != null) {
                    insertBeforeDayIndex = plan.index();
                    // 자동으로 끼워 넣은 도착 시각도 30분 단위로 스냅해 저장(신규 스마트 일정 규칙과 동일)
                    scheduledTime = VisitTiming.snapToNext30Min(plan.arrival()).format(TIME_FMT);
                }
            }
        }

        ItineraryItem item = ItineraryItem.builder()
                .itinerary(itinerary)
                .contentId(request.getContentId())
                .contentTypeId(request.getContentTypeId())
                .placeName(request.getPlaceName())
                .thumbnailUrl(request.getThumbnailUrl())
                .scheduledTime(scheduledTime)
                .tags(tags)
                .crowdRate(request.getCrowdRate())
                .visitDate(visitDate)
                .addr1(request.getAddr1())
                .tel(request.getTel())
                .useFeeText(request.getUseFeeText())
                .isFree(request.getIsFree())
                .estimatedCostPerPerson(request.getEstimatedCostPerPerson())
                .restDateText(request.getRestDateText())
                .closeTime(request.getCloseTime())
                .useTimeText(request.getUseTimeText())
                .homepageUrl(request.getHomepageUrl())
                .overview(request.getOverview())
                .detailFacts(request.getDetailFacts())
                .indoorYn(situational.getIndoorYn())
                .strollerFriendly(request.getStrollerFriendly())
                .accessibleFriendly(Boolean.TRUE.equals(request.getAccessibleFriendly()))
                .category(request.getCategory())
                .isAlternate(Boolean.TRUE.equals(request.getIsAlternate()))
                .mapX(request.getMapX())
                .mapY(request.getMapY())
                .backupContentId(request.getBackupContentId())
                .backupContentTypeId(request.getBackupContentTypeId())
                .backupPlaceName(request.getBackupPlaceName())
                .build();

        if (insertBeforeDayIndex != null) {
            reflowInsert(dayItems, insertBeforeDayIndex, item, ClosingTimeGate.parseHhMm(scheduledTime));
        } else {
            item.setDisplayOrder(itinerary.getItems().size());
        }
        itinerary.getItems().add(item);
        if (logHistory) {
            String reason = Boolean.TRUE.equals(request.getIsAlternate())
                    ? "대안에서 '" + item.getPlaceName() + "' 추가"
                    : "검색에서 '" + item.getPlaceName() + "' 추가";
            planHistoryService.recordChange(itinerary, beforeSnapshot, "MANUAL", reason,
                    item.getContentId(), item.getPlaceName());
        }
        log.info("[addItem] 저장 itineraryId={} place={} contentId={} time={} logHistory={}",
                itineraryId, item.getPlaceName(), item.getContentId(), scheduledTime, logHistory);
        return itineraryRepository.save(itinerary);
    }

    /** 같은 날 같은 contentId가 이미 있으면 중복 행을 만들지 않는다. */
    private static boolean alreadyHasPlace(Itinerary itinerary, String contentId, LocalDate visitDate) {
        if (contentId == null || contentId.isBlank()) {
            return false;
        }
        return itinerary.getItems().stream().anyMatch(item -> {
            if (!contentId.equals(item.getContentId())) {
                return false;
            }
            LocalDate itemDate = item.getVisitDate() != null ? item.getVisitDate() : itinerary.getStartDate();
            return visitDate.equals(itemDate);
        });
    }

    /**
     * 명시된 시각으로 넣거나 옮길 때: 같은 날 겹침을 먼저, 그다음 마감. 둘 다면 겹침만 노출.
     * 겹침은 최소 체류(packing)로만 본다 - 계획 체류만큼 비워 두지 않아도 담을 수 있게.
     * skipClosing=true면 마감은 사용자가 이미 경고를 보고 넘어간 것이므로 저장을 막지 않는다.
     */
    private void assertScheduleFeasible(Itinerary itinerary, LocalDate visitDate, String scheduledTime,
                                        Long excludeItemId, LocalTime close, int stayMinutes, int closeBuffer,
                                        boolean skipClosing) {
        LocalTime start = ClosingTimeGate.parseHhMm(scheduledTime);
        if (start == null) {
            return;
        }
        List<TimeConflictGate.Occupant> occupants = occupantsOf(itinerary, visitDate);
        LocalTime end = VisitTiming.occupancyEnd(start, stayMinutes, close);
        TimeConflictGate.CheckResult overlap = TimeConflictGate.check(start, end, occupants, excludeItemId);
        if (overlap.blocked()) {
            throw new TimeSlotConflictException(overlap.message(), overlap.conflictingItemId(),
                    overlap.conflictingPlaceName(), overlap.conflictingTime(),
                    suggestTimes(start, close, stayMinutes, occupants, visitDate, excludeItemId, closeBuffer));
        }
        if (skipClosing) {
            return;
        }
        ClosingTimeGate.CheckResult closing = ClosingTimeGate.check(close, start, closeBuffer);
        if (closing.blocked()) {
            throw new ClosingTimeInfeasibleException(closing.message(), closing.closeTime(),
                    closing.latestArrivalBy(),
                    suggestTimes(start, close, stayMinutes, occupants, visitDate, excludeItemId, closeBuffer));
        }
    }

    private List<String> suggestTimes(LocalTime preferred, LocalTime close, int stayMinutes,
                                      List<TimeConflictGate.Occupant> occupants, LocalDate visitDate,
                                      Long excludeItemId, int closeBuffer) {
        List<VisitTiming.Occupied> occupied = occupants.stream()
                .map(o -> new VisitTiming.Occupied(o.itemId(), o.placeName(), o.start(), o.end()))
                .toList();
        return VisitTiming.suggestAlternativeStarts(preferred, close, stayMinutes, occupied,
                resolveDayStart(visitDate), excludeItemId, closeBuffer);
    }

    private List<TimeConflictGate.Occupant> occupantsOf(Itinerary itinerary, LocalDate visitDate) {
        return dayItemsSorted(itinerary, visitDate).stream()
                .map(i -> new TimeConflictGate.Occupant(
                        i.getId(), i.getPlaceName(),
                        ClosingTimeGate.parseHhMm(i.getScheduledTime()),
                        VisitTiming.occupancyEndPacking(i)))
                .toList();
    }

    private List<ItineraryItem> dayItemsSorted(Itinerary itinerary, LocalDate visitDate) {
        return itinerary.getItems().stream()
                .filter(i -> visitDate == null
                        || visitDate.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && visitDate.equals(itinerary.getStartDate())))
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .collect(Collectors.toList());
    }

    private LocalTime estimateArrivalTime(Itinerary itinerary, LocalDate visitDate, String mapX, String mapY) {
        List<ItineraryItem> dayItems = dayItemsSorted(itinerary, visitDate);
        LocalTime cursor;
        ItineraryItem last = dayItems.isEmpty() ? null : dayItems.get(dayItems.size() - 1);
        if (last != null && last.getScheduledTime() != null) {
            LocalTime lastStart = ClosingTimeGate.parseHhMm(last.getScheduledTime());
            LocalTime lastEnd = VisitTiming.occupancyEnd(last);
            cursor = lastEnd != null ? lastEnd : (lastStart != null ? lastStart.plusMinutes(VisitTiming.ATTRACTION_STAY_MINUTES) : LocalTime.of(9, 0));
        } else {
            cursor = resolveDayStart(visitDate);
        }
        int travel = travelMinutes(last == null ? null : last.getMapX(), last == null ? null : last.getMapY(), mapX, mapY);
        return cursor.plusMinutes(travel);
    }

    /** 오늘(KST)이면 지금부터 30분 뒤(반시간 단위 반올림), 미래 날짜면 하루 전체를 쓸 수 있으니 09:00 */
    private LocalTime resolveDayStart(LocalDate visitDate) {
        if (visitDate == null || !visitDate.equals(KoreaClock.today())) {
            return LocalTime.of(9, 0);
        }
        LocalTime soon = KoreaClock.nowTime().plusMinutes(30).withSecond(0).withNano(0);
        int m = soon.getMinute();
        LocalTime rounded;
        if (m == 0) {
            rounded = soon;
        } else if (m <= 30) {
            rounded = soon.withMinute(30);
        } else {
            rounded = soon.plusHours(1).withMinute(0);
        }
        return rounded.isBefore(LocalTime.of(9, 0)) ? LocalTime.of(9, 0) : rounded;
    }

    /** Haversine 기반 이동시간 추정 - 좌표 없으면 기본 10분, 가까우면 5분부터. */
    private int travelMinutes(String mapX1, String mapY1, String mapX2, String mapY2) {
        return GeoUtils.estimateTravelMinutes(mapX1, mapY1, mapX2, mapY2);
    }

    private record InsertionPlan(int index, LocalTime arrival) {
    }

    /**
     * 하루 맨 끝은 마감 때문에 막혔을 때, 마감을 넘기지 않는 자리 중 가장 늦은 위치(day-local index,
     * 그 인덱스 "앞"에 끼워 넣는다는 뜻)를 뒤에서부터 찾는다 - 앞쪽 일정을 최대한 안 건드리기 위함.
     * 못 찾으면 null(어디에도 못 들어감 - 기존처럼 차단해야 함).
     */
    private InsertionPlan findFeasibleInsertion(List<ItineraryItem> dayItems, LocalDate visitDate,
                                                  String mapX, String mapY, LocalTime close, int closeBuffer) {
        for (int p = dayItems.size() - 1; p >= 0; p--) {
            ItineraryItem predecessor = p == 0 ? null : dayItems.get(p - 1);
            LocalTime prevEnd = predecessor == null
                    ? resolveDayStart(visitDate)
                    : (VisitTiming.occupancyEnd(predecessor) != null
                            ? VisitTiming.occupancyEnd(predecessor)
                            : parseOrDefault(predecessor.getScheduledTime()).plusMinutes(VisitTiming.ATTRACTION_STAY_MINUTES));
            int travelToNew = travelMinutes(
                    predecessor == null ? null : predecessor.getMapX(),
                    predecessor == null ? null : predecessor.getMapY(),
                    mapX, mapY);
            LocalTime arrival = prevEnd.plusMinutes(travelToNew);
            if (ClosingTimeGate.check(close, arrival, closeBuffer).allowed()) {
                return new InsertionPlan(p, arrival);
            }
        }
        return null;
    }

    private static LocalTime parseOrDefault(String hhmm) {
        LocalTime t = ClosingTimeGate.parseHhMm(hhmm);
        return t != null ? t : LocalTime.of(9, 0);
    }

    /**
     * 재계산된(자동 추정) 시각을 저장 규칙과 동일하게 30분 단위로 올림 스냅한다. 스냅 결과가 직전
     * 배정 시각과 같거나 이르면(중복·역전) 직전+30분으로 민다. 호출부는 반환값을 다음 계산의
     * 커서로 이어 써서 스냅된 값 기준으로 체류·이동을 누적한다.
     */
    private static LocalTime snapForward(LocalTime computed, LocalTime prevAssigned) {
        LocalTime snapped = VisitTiming.snapToNext30Min(computed);
        if (snapped != null && prevAssigned != null && !snapped.isAfter(prevAssigned)) {
            snapped = prevAssigned.plusMinutes(VisitTiming.SCHEDULE_SNAP_MINUTES);
        }
        return snapped;
    }

    /**
     * index 위치(그 자리에 있던 항목부터) 뒤로 밀며 표시순서·시각을 다시 계산한다(같은 Haversine
     * 추정 공식). 실제 도로 이동시간까지 반영하려면 사용자가 별도로 "동선 재계산"을 돌려야 한다.
     */
    private void reflowInsert(List<ItineraryItem> dayItems, int index, ItineraryItem newItem, LocalTime start) {
        for (int i = 0; i < index; i++) {
            dayItems.get(i).setDisplayOrder(i);
        }
        newItem.setDisplayOrder(index);
        LocalTime cursor = start;
        LocalTime prevAssigned = start;
        ItineraryItem prev = newItem;
        for (int i = index; i < dayItems.size(); i++) {
            ItineraryItem next = dayItems.get(i);
            int travel = travelMinutes(prev.getMapX(), prev.getMapY(), next.getMapX(), next.getMapY());
            cursor = VisitTiming.occupancyEnd(cursor, VisitTiming.stayMinutes(prev), VisitTiming.resolveCloseTime(prev))
                    .plusMinutes(travel);
            LocalTime assigned = snapForward(cursor, prevAssigned);
            next.setScheduledTime(assigned.format(TIME_FMT));
            next.setDisplayOrder(i + 1);
            prevAssigned = assigned;
            cursor = assigned;
            prev = next;
        }
    }

    @Transactional
    public Itinerary updateItem(Long itineraryId, Long itemId, UpdateItineraryItemRequest request) {
        Itinerary itinerary = get(itineraryId);
        ItineraryItem item = itinerary.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("일정 항목을 찾을 수 없습니다: " + itemId));

        if (request.getIsPinned() != null) {
            item.setPinned(request.getIsPinned());
        }
        if (request.getPinnedReason() != null) {
            item.setPinnedReason(request.getPinnedReason());
        }
        if (request.getDisplayOrder() != null) {
            item.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getScheduledTime() != null) {
            LocalDate targetVisitDate = (request.getVisitDate() != null && !request.getVisitDate().isBlank())
                    ? LocalDate.parse(request.getVisitDate())
                    : item.getVisitDate();
            LocalTime close = VisitTiming.resolveCloseTime(item);
            int packingStay = VisitTiming.packingStayMinutes(item);
            int closeBuffer = VisitTiming.closeBufferMinutes(
                    item.getCloseTime(), item.getUseTimeText(), item.getDetailFacts());
            assertScheduleFeasible(itinerary, targetVisitDate, request.getScheduledTime(), item.getId(),
                    close, packingStay, closeBuffer,
                    Boolean.TRUE.equals(request.getAcknowledgeHoursWarning()));
            item.setScheduledTime(request.getScheduledTime());
        }
        if (request.getVisitDate() != null && !request.getVisitDate().isBlank()) {
            item.setVisitDate(LocalDate.parse(request.getVisitDate()));
        }
        if (request.getPlaceName() != null && !request.getPlaceName().isBlank()) {
            item.setPlaceName(request.getPlaceName().trim());
        }
        if (request.getTags() != null) {
            item.setTags(PlaceTagSanitizer.sanitizeStored(
                    request.getTags(), item.getContentTypeId(), item.getPlaceName(), item.getCategory()));
        }
        if (request.getAddr1() != null) {
            item.setAddr1(request.getAddr1().isBlank() ? null : request.getAddr1().trim());
        }
        if (request.getTel() != null) {
            item.setTel(request.getTel().isBlank() ? null : request.getTel().trim());
        }
        if (request.getUseFeeText() != null) {
            item.setUseFeeText(request.getUseFeeText().isBlank() ? null : request.getUseFeeText().trim());
        }
        if (request.getIsFree() != null) {
            item.setIsFree(request.getIsFree());
            if (Boolean.TRUE.equals(request.getIsFree())) {
                item.setEstimatedCostPerPerson(0);
            }
        }
        if (request.getEstimatedCostPerPerson() != null) {
            item.setEstimatedCostPerPerson(request.getEstimatedCostPerPerson());
        }
        if (request.getRestDateText() != null) {
            item.setRestDateText(request.getRestDateText().isBlank() ? null : request.getRestDateText().trim());
        }
        if (request.getCategory() != null) {
            item.setCategory(request.getCategory().isBlank() ? null : request.getCategory().trim());
        }
        if (request.getIndoorYn() != null || request.getRainSensitivity() != null
                || request.getCongestionSensitivity() != null) {
            if (item.getContentId() != null) {
                PlaceSituationalTags saved = situationalTagService.saveManual(
                        item.getContentId(), request.getIndoorYn(),
                        request.getRainSensitivity(), request.getCongestionSensitivity());
                item.setIndoorYn(saved.getIndoorYn());
                List<String> nextTags = item.getTags() == null ? new ArrayList<>() : new ArrayList<>(item.getTags());
                if (Boolean.TRUE.equals(saved.getIndoorYn())) {
                    if (!nextTags.contains("#실내")) {
                        nextTags.add(0, "#실내");
                    }
                } else {
                    nextTags.remove("#실내");
                }
                item.setTags(PlaceTagSanitizer.sanitizeStored(
                        nextTags, item.getContentTypeId(), item.getPlaceName(), item.getCategory()));
            }
        }
        return itineraryRepository.save(itinerary);
    }

    /**
     * 삭제 - 예비 후보(backupContentId, 슬롯 생성 시점에 담아둔 대표 다음으로 가까웠던 후보)가 있으면
     * 그 자리에 자동으로 대체를 시도한다. 예비 후보의 유효성은 삭제 시점에 다시 확인한다(생성 이후
     * 마감/휴무가 바뀌었을 수 있어 스냅샷을 그대로 믿지 않음) - 무효하면(또는 예비 후보 자체가 없으면)
     * 빈 자리로 둔다.
     * ⚠ 2026-08-21: 최초 구현엔 예비 후보가 없거나 무효할 때 파이프라인(Stage1~4, 외부 API 여러 건)을
     * 다시 돌리는 재조회 폴백이 있었으나, 삭제는 지금까지 즉시 끝나던 가벼운 동작이었는데 이 폴백이
     * @Transactional 트랜잭션 안에서 수 초짜리 외부 API 체인을 동기 블로킹으로 돌리면서, 예비 후보가
     * 없는 대다수 항목(이 기능 배포 전에 이미 담겨 있던 모든 항목 포함)의 삭제가 느려지거나 타임아웃/
     * 오류로 아예 실패하는 회귀가 발생함(사용자 제보: "삭제 버튼이 안 먹는다"). 프론트도 이 실패를
     * 못 잡아 버튼이 그냥 반응 없는 것처럼 보였음. 재조회 폴백은 제거하고, 예비 후보(단건 캐시 조회,
     * 가벼움)가 있을 때만 대체를 시도하도록 축소했다.
     */
    @Transactional
    public DeleteItemResult deleteItem(Long itineraryId, Long itemId) {
        return deleteItem(itineraryId, itemId, true, false);
    }

    /**
     * @param reflowTimes false면 시각을 그대로 둔다(프론트가 같은 슬롯에 대체 장소를 곧 넣을 때).
     */
    @Transactional
    public DeleteItemResult deleteItem(Long itineraryId, Long itemId, boolean reflowTimes) {
        return deleteItem(itineraryId, itemId, reflowTimes, false);
    }

    /**
     * @param replaceBackup true일 때만 예비 후보로 자리를 채운다. 기본 삭제는 빈 자리로 둔다 —
     *                      자리를 비워 DDP 같은 장소를 넣으려다 예비 장소로 다시 채워지는 오해를 막기 위함.
     */
    @Transactional
    public DeleteItemResult deleteItem(Long itineraryId, Long itemId, boolean reflowTimes, boolean replaceBackup) {
        Itinerary itinerary = get(itineraryId);
        ItineraryItem removed = itinerary.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElse(null);
        if (removed == null) {
            return new DeleteItemResult(itineraryRepository.save(itinerary), null);
        }
        // 사용자의 직접 삭제(개별 삭제·대안 카드 삭제)는 전부 변경 이력에 MANUAL로 남긴다.
        PlanSnapshot beforeSnapshot = planHistoryService.snapshotOf(itinerary);
        String removedName = removed.getPlaceName();
        String backupContentId = removed.getBackupContentId();
        Integer backupContentTypeId = removed.getBackupContentTypeId();
        String scheduledTime = removed.getScheduledTime();
        LocalDate visitDate = removed.getVisitDate();
        int displayOrder = removed.getDisplayOrder();
        itinerary.getItems().removeIf(i -> i.getId().equals(itemId));

        ItineraryItem replacement = null;
        if (replaceBackup && backupContentId != null && backupContentTypeId != null) {
            replacement = tryBackupReplacement(itinerary, backupContentId, backupContentTypeId,
                    scheduledTime, visitDate, displayOrder);
        }
        if (replacement != null) {
            replacement.setItinerary(itinerary);
            itinerary.getItems().add(replacement);
        } else if (reflowTimes) {
            reflowDayAfterRemoval(itinerary, visitDate, displayOrder, scheduledTime);
        }

        if (replacement != null) {
            planHistoryService.recordChange(itinerary, beforeSnapshot, "MANUAL",
                    "'" + removedName + "' → '" + replacement.getPlaceName() + "' 교체",
                    replacement.getContentId(), replacement.getPlaceName());
        } else {
            planHistoryService.recordChange(itinerary, beforeSnapshot, "MANUAL",
                    "'" + removedName + "' 삭제", null, null);
        }
        Itinerary saved = itineraryRepository.save(itinerary);
        return new DeleteItemResult(saved, replacement == null ? null : replacement.getPlaceName());
    }

    public record DeleteItemResult(Itinerary itinerary, String autoReplacedPlaceName) {
    }

    /**
     * 슬롯 삭제 후 남은 같은 날 항목의 시각을 앞으로 당긴다. 구멍 앞은 그대로 두고, 구멍 뒤는
     * 직전 슬롯 체류+이동(Haversine 추정, 카카오 호출 없음)만큼 이어 붙인다. 첫 슬롯을 지웠으면
     * 지워진 시각(또는 하루 시작)부터 채운다.
     */
    private void reflowDayAfterRemoval(Itinerary itinerary, LocalDate visitDate, int removedOrder,
                                       String removedTime) {
        List<ItineraryItem> dayItems = dayItemsSorted(itinerary, visitDate);
        if (dayItems.isEmpty()) {
            return;
        }
        List<ItineraryItem> before = new ArrayList<>();
        List<ItineraryItem> after = new ArrayList<>();
        for (ItineraryItem item : dayItems) {
            if (item.getDisplayOrder() < removedOrder) {
                before.add(item);
            } else {
                after.add(item);
            }
        }
        int order = 0;
        for (ItineraryItem item : before) {
            item.setDisplayOrder(order++);
        }
        if (after.isEmpty()) {
            return;
        }
        LocalTime cursor;
        ItineraryItem prev;
        LocalTime prevAssigned;
        if (before.isEmpty()) {
            cursor = ClosingTimeGate.parseHhMm(removedTime);
            if (cursor == null) {
                cursor = resolveDayStart(visitDate);
            }
            prev = null;
            prevAssigned = null;
        } else {
            prev = before.get(before.size() - 1);
            LocalTime prevEnd = VisitTiming.occupancyEnd(prev);
            cursor = prevEnd != null ? prevEnd : parseOrDefault(prev.getScheduledTime());
            prevAssigned = parseOrDefault(prev.getScheduledTime());
        }
        for (int i = 0; i < after.size(); i++) {
            ItineraryItem next = after.get(i);
            if (prev != null) {
                cursor = cursor.plusMinutes(travelMinutes(prev.getMapX(), prev.getMapY(),
                        next.getMapX(), next.getMapY()));
            }
            LocalTime assigned = snapForward(cursor, prevAssigned);
            next.setScheduledTime(assigned.format(TIME_FMT));
            next.setDisplayOrder(order++);
            prevAssigned = assigned;
            LocalTime end = VisitTiming.occupancyEnd(next);
            cursor = end != null ? end : assigned.plusMinutes(VisitTiming.stayMinutes(next));
            prev = next;
        }
    }

    /** 예비 후보 스냅샷을 지금 다시 조회해 유효성(마감·시간겹침)을 확인한 뒤에만 대체 아이템을 만든다 */
    private ItineraryItem tryBackupReplacement(Itinerary itinerary, String contentId, int contentTypeId,
                                                String scheduledTime, LocalDate visitDate, int displayOrder) {
        TourAttractionDetail detail;
        try {
            detail = tourAttractionService.getDetail(contentId, contentTypeId).block();
        } catch (Exception e) {
            log.warn("[deleteItem] 예비 후보 상세조회 실패 contentId={} - 폴백으로 넘어감", contentId, e);
            return null;
        }
        if (detail == null) {
            return null;
        }
        String useTimeText = BusinessHoursEvaluator.extractUseTimeText(detail.getIntroFields());
        LocalTime close = BusinessHoursEvaluator.extractCloseTime(detail.getIntroFields());
        if (!isSlotStillValid(itinerary, visitDate, scheduledTime, close, null)) {
            return null;
        }
        String useFeeText = BusinessHoursEvaluator.extractUseFeeText(detail.getIntroFields());
        String placeName = detail.getTitle() != null && !detail.getTitle().isBlank()
                ? detail.getTitle() : null;
        if (placeName == null) {
            return null;
        }
        return ItineraryItem.builder()
                .contentId(contentId)
                .contentTypeId(contentTypeId)
                .placeName(placeName)
                .mapX(detail.getMapX())
                .mapY(detail.getMapY())
                .scheduledTime(scheduledTime)
                .visitDate(visitDate)
                .addr1(detail.getAddr1())
                .tel(BusinessHoursEvaluator.extractPhone(detail.getTel(), detail.getIntroFields()))
                .useFeeText(useFeeText)
                .isFree(BusinessHoursEvaluator.isFree(useFeeText))
                .estimatedCostPerPerson(BusinessHoursEvaluator.extractCostAmount(useFeeText))
                .restDateText(BusinessHoursEvaluator.extractRestDateText(detail.getIntroFields()))
                .closeTime(BusinessHoursEvaluator.formatHhMm(close))
                .useTimeText(useTimeText)
                .homepageUrl(detail.getHomepage())
                .displayOrder(displayOrder)
                .isAlternate(true)
                .build();
    }

    /** 마감시간·시간겹침 둘 다 지금 시점 기준으로 통과해야 대체를 실행한다 */
    private boolean isSlotStillValid(Itinerary itinerary, LocalDate visitDate, String scheduledTime,
                                      LocalTime close, Long excludeItemId) {
        LocalTime arrival = ClosingTimeGate.parseHhMm(scheduledTime);
        if (arrival == null) {
            return true;
        }
        LocalTime resolvedClose = close;
        if (resolvedClose == null) {
            resolvedClose = VisitTiming.DEFAULT_CLOSE_OTHER;
        }
        int buffer = close != null ? BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES : 0;
        if (ClosingTimeGate.check(resolvedClose, arrival, buffer).blocked()) {
            return false;
        }
        List<TimeConflictGate.Occupant> occupants = occupantsOf(itinerary, visitDate);
        LocalTime end = VisitTiming.occupancyEnd(arrival, VisitTiming.defaultPackingStayMinutes(), resolvedClose);
        return !TimeConflictGate.check(arrival, end, occupants, excludeItemId).blocked();
    }

    /** 일자별 페이지 확정/해제 - 프론트가 "다음 날로 이동"을 허용할지 판단하는 기준 */
    @Transactional
    public Itinerary confirmDay(Long itineraryId, LocalDate date, boolean confirmed) {
        Itinerary itinerary = get(itineraryId);
        if (confirmed) {
            itinerary.getConfirmedDates().add(date);
        } else {
            itinerary.getConfirmedDates().remove(date);
        }
        return itineraryRepository.save(itinerary);
    }

    /** 공유 토큰 발급(또는 기존 토큰 재사용) */
    @Transactional
    public SharedItineraryResponse createShare(Long itineraryId) {
        Itinerary itinerary = get(itineraryId);
        if (itinerary.getShareToken() == null || itinerary.getShareToken().isBlank()) {
            itinerary.setShareToken(UUID.randomUUID().toString().replace("-", ""));
            itinerary = itineraryRepository.save(itinerary);
        }
        return toShared(itinerary);
    }

    @Transactional(readOnly = true)
    public SharedItineraryResponse getShared(String token) {
        Itinerary itinerary = itineraryRepository.findByShareToken(token)
                .orElseThrow(() -> new EntityNotFoundException("공유 일정을 찾을 수 없습니다"));
        return toShared(itinerary);
    }

    /**
     * 동선 재계산 — 카카오 이동시간 매트릭스 TSP + 체류·이동·휴무 반영 시간표.
     * originLon/Lat(WGS84)가 있으면 GPS를 시작점으로 둔다. 키 없으면 직선거리 폴백.
     */
    @Transactional
    public OptimizeRouteResult optimizeRoute(Long itineraryId, LocalDate date,
                                             Double originLon, Double originLat) {
        return optimizeRoute(itineraryId, date, originLon, originLat, null);
    }

    /**
     * @param startTime "HH:mm" - 첫 장소 도착 시각을 사용자가 직접 지정(선택). 주어지면 오늘/미래
     *                  자동 판정을 건너뛰고 이 시각부터 시작해, 나머지는 실제 체류·이동시간만큼
     *                  자연스럽게 이어 붙는다.
     */
    @Transactional
    public OptimizeRouteResult optimizeRoute(Long itineraryId, LocalDate date,
                                             Double originLon, Double originLat, String startTime) {
        Itinerary itinerary = get(itineraryId);
        List<ItineraryItem> dayItems = itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .collect(Collectors.toList());
        if (dayItems.size() < 2) {
            return new OptimizeRouteResult(itinerary, null, null);
        }

        // 고정(pin)한 앵커 - 시각이 박혀 있으면 자리·시각을 그대로 두고, 나머지만 최단 순서로 다시 잡는다.
        List<ItineraryItem> pinned = dayItems.stream()
                .filter(i -> i.isPinned() && ClosingTimeGate.parseHhMm(i.getScheduledTime()) != null)
                .collect(Collectors.toList());
        List<ItineraryItem> movable = dayItems.stream()
                .filter(i -> !pinned.contains(i))
                .collect(Collectors.toList());

        LocalTime overrideStartTime = ClosingTimeGate.parseHhMm(startTime);
        List<ItineraryItem> finalOrder;
        String message;
        if (movable.size() < 2) {
            // 다시 잡을 게 없다(전부 고정이거나 이동 가능 1곳뿐) - 순서만 유지
            finalOrder = dayItems;
            message = null;
        } else {
            RouteRecalculationService.Result recalc =
                    routeRecalculationService.recalculate(movable, originLon, originLat, overrideStartTime);
            finalOrder = pinned.isEmpty()
                    ? recalc.ordered()
                    : mergeByScheduledTime(recalc.ordered(), pinned);
            message = recalc.message();
            if (message != null && !pinned.isEmpty()) {
                message = message + " 고정한 일정은 그대로 뒀어요.";
            }
        }

        int orderBase = itinerary.getItems().stream()
                .filter(i -> dayItems.stream().noneMatch(t -> t.getId().equals(i.getId())))
                .mapToInt(ItineraryItem::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
        for (int i = 0; i < finalOrder.size(); i++) {
            finalOrder.get(i).setDisplayOrder(orderBase + i);
        }
        Itinerary saved = itineraryRepository.save(itinerary);

        String oLon = originLon != null ? String.valueOf(originLon) : null;
        String oLat = originLat != null ? String.valueOf(originLat) : null;
        double km = VisitOrderOptimizer.pathDistanceKm(
                finalOrder.stream().filter(this::itemHasCoords).toList(),
                oLon, oLat,
                ItineraryItem::getMapX, ItineraryItem::getMapY);
        return new OptimizeRouteResult(saved, message, km);
    }

    /**
     * 최단 순서로 다시 잡힌 이동 항목들(recalculated) 사이사이에 고정 앵커를 <b>제 시각 위치</b>로
     * 끼워 넣는다 - 앵커의 scheduledTime보다 늦은 첫 항목 앞에 둔다. 앵커의 시각·체류는 손대지 않는다.
     */
    private static List<ItineraryItem> mergeByScheduledTime(List<ItineraryItem> recalculated,
                                                            List<ItineraryItem> pinned) {
        List<ItineraryItem> merged = new ArrayList<>(recalculated);
        for (ItineraryItem anchor : pinned) {
            LocalTime at = ClosingTimeGate.parseHhMm(anchor.getScheduledTime());
            int pos = 0;
            while (pos < merged.size()) {
                LocalTime t = ClosingTimeGate.parseHhMm(merged.get(pos).getScheduledTime());
                if (t != null && at != null && t.isAfter(at)) {
                    break;
                }
                pos++;
            }
            merged.add(pos, anchor);
        }
        return merged;
    }

    /**
     * 그리디 제안 순서를 사용자가 확인한 뒤에만 displayOrder·scheduledTime을 반영한다.
     * 알고리즘을 다시 돌리지 않는다.
     */
    @Transactional
    public Itinerary applySuggestedRoute(Long itineraryId, LocalDate date,
                                         ApplySuggestedRouteRequest request) {
        Itinerary itinerary = get(itineraryId);
        List<ItineraryItem> targets = itemsOnDate(itinerary, date);
        if (request == null || request.getStops() == null || request.getStops().isEmpty()) {
            throw new IllegalArgumentException("반영할 순서가 없어요.");
        }
        if (request.getStops().size() != targets.size()) {
            throw new IllegalArgumentException("제안 장소 수가 오늘 일정과 달라요.");
        }
        Map<Long, ItineraryItem> byId = targets.stream()
                .collect(Collectors.toMap(ItineraryItem::getId, i -> i));
        List<Long> seen = new ArrayList<>();
        for (ApplySuggestedRouteRequest.Stop stop : request.getStops()) {
            if (stop.getItemId() == null || !byId.containsKey(stop.getItemId())) {
                throw new IllegalArgumentException("오늘 일정에 없는 장소가 포함돼 있어요.");
            }
            if (seen.contains(stop.getItemId())) {
                throw new IllegalArgumentException("같은 장소가 순서에 두 번 들어 있어요.");
            }
            seen.add(stop.getItemId());
        }

        int orderBase = itinerary.getItems().stream()
                .filter(i -> targets.stream().noneMatch(t -> t.getId().equals(i.getId())))
                .mapToInt(ItineraryItem::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
        for (int i = 0; i < request.getStops().size(); i++) {
            ApplySuggestedRouteRequest.Stop stop = request.getStops().get(i);
            ItineraryItem item = byId.get(stop.getItemId());
            item.setDisplayOrder(orderBase + i);
            if (stop.getScheduledTime() != null && !stop.getScheduledTime().isBlank()) {
                item.setScheduledTime(stop.getScheduledTime().trim());
            }
        }
        return itineraryRepository.save(itinerary);
    }

    private List<ItineraryItem> itemsOnDate(Itinerary itinerary, LocalDate date) {
        return itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .collect(Collectors.toList());
    }

    /** 하위 호환 */
    @Transactional
    public Itinerary optimizeRoute(Long itineraryId, LocalDate date) {
        return optimizeRoute(itineraryId, date, null, null).itinerary();
    }

    public record OptimizeRouteResult(Itinerary itinerary, String message, Double totalDistanceKm) {
    }

    // ── 대안 일정(원본 + 변경 이력) ──────────────────────────────────────────────

    /**
     * 대안 채택 통합 처리 - 기존 항목 삭제 + 대안 추가(+ 선택적 동선 재계산)를 한 트랜잭션으로 하고
     * 변경 이력을 한 건만 남긴다. 실패(시간겹침·마감불가 등) 시 전체 롤백 - 예전 프론트 3단계 호출은
     * 삭제만 되고 추가가 실패하면 빈 슬롯이 남던 문제가 있었다.
     */
    @Transactional
    public ApplyAlternativeResult applyAlternative(Long itineraryId, ApplyAlternativeRequest req) {
        Itinerary itinerary = get(itineraryId);
        PlanSnapshot before = planHistoryService.snapshotOf(itinerary);

        if (req.getRemovedItemId() != null) {
            itinerary.getItems().removeIf(i -> i.getId().equals(req.getRemovedItemId()));
        }

        AddItineraryItemRequest add = req.getNewPlace();
        add.setIsAlternate(Boolean.TRUE);
        Itinerary withNew = addItem(itineraryId, add);

        String routeHint = null;
        Double km = null;
        if (req.isReoptimize()) {
            LocalDate date = add.getVisitDate() != null ? add.getVisitDate() : withNew.getStartDate();
            OptimizeRouteResult r = optimizeRoute(itineraryId, date, null, null, null);
            withNew = r.itinerary();
            routeHint = r.message();
            km = r.totalDistanceKm();
        }

        String trigger = normalizeTrigger(req.getTriggerType());
        String reason = req.getReason() != null && !req.getReason().isBlank()
                ? req.getReason()
                : defaultChangeReason(trigger);
        planHistoryService.recordChange(withNew, before, trigger, reason,
                add.getContentId(), add.getPlaceName());
        Itinerary saved = itineraryRepository.save(withNew);
        return new ApplyAlternativeResult(saved, add.getPlaceName(), routeHint, km);
    }

    public record ApplyAlternativeResult(Itinerary itinerary, String newPlaceName,
                                         String routeHint, Double totalDistanceKm) {
    }

    /**
     * "바람이가 동선 최적화" 전용 - 동선을 재계산하고, 실제로 순서·시각이 바뀐 경우에만 변경 이력
     * (triggerType=ROUTE)으로 남긴다. optimize-route는 GPS 시작·일자 확정 등 여러 곳에서 불려서
     * 이력을 남기지 않는다. 사용자가 여러 번 눌러도 바뀐 게 없으면 이력이 쌓이지 않는다.
     */
    @Transactional
    public OptimizeRouteResult applyReroute(Long itineraryId, LocalDate date,
                                            Double originLon, Double originLat, String startTime,
                                            String reason) {
        Itinerary itinerary = get(itineraryId);
        PlanSnapshot before = planHistoryService.snapshotOf(itinerary);
        OptimizeRouteResult r = optimizeRoute(itineraryId, date, originLon, originLat, startTime);
        Itinerary after = r.itinerary();
        boolean changed = !planHistoryService.sameStops(before, planHistoryService.snapshotOf(after));
        if (changed) {
            planHistoryService.recordChange(after, before, "ROUTE",
                    reason != null && !reason.isBlank() ? reason : "동선 재계산", null, null);
        }
        Itinerary saved = itineraryRepository.save(after);
        String msg = changed ? r.message() : "이미 이동을 최소화한 순서예요. 그대로 두었어요.";
        return new OptimizeRouteResult(saved, msg, r.totalDistanceKm());
    }

    /**
     * 원본 또는 특정 변경 이력 시점으로 되돌린다. 되돌리기 자체도 새 변경 이력(REVERT)으로 남는다.
     * @param targetSequence null이면 원본으로
     */
    @Transactional
    public Itinerary revertPlan(Long itineraryId, Integer targetSequence) {
        Itinerary itinerary = get(itineraryId);
        PlanSnapshot reverted = planHistoryService.revert(itinerary, targetSequence);
        if (reverted == null) {
            throw new IllegalArgumentException(targetSequence == null
                    ? "되돌릴 원본이 아직 없어요"
                    : "변경 이력 #" + targetSequence + "을(를) 찾을 수 없어요");
        }
        return itineraryRepository.save(itinerary);
    }

    private static String normalizeTrigger(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MANUAL";
        }
        return switch (raw.trim().toUpperCase()) {
            case "WEATHER", "RAIN" -> "WEATHER";
            case "HEAT" -> "HEAT";
            case "CROWD" -> "CROWD";
            case "ROUTE" -> "ROUTE";
            default -> "MANUAL";
        };
    }

    private static String defaultChangeReason(String trigger) {
        return switch (trigger) {
            case "WEATHER" -> "비 예보로 실내 코스로 대체";
            case "HEAT" -> "폭염으로 실내 코스로 대체";
            case "CROWD" -> "혼잡으로 한산한 곳으로 대체";
            case "ROUTE" -> "동선을 줄이려 장소 교체";
            default -> "장소 교체";
        };
    }

    private boolean itemHasCoords(ItineraryItem item) {
        return item.getMapX() != null && !item.getMapX().isBlank()
                && item.getMapY() != null && !item.getMapY().isBlank();
    }

    /**
     * 해당 일자 일정을 scheduledTime(HH:mm) 오름차순으로 재정렬하고 displayOrder를 맞춘다.
     * 시각이 없는 항목은 맨 뒤. 시각 값은 그대로 둔다.
     */
    @Transactional
    public Itinerary sortByScheduledTime(Long itineraryId, LocalDate date) {
        Itinerary itinerary = get(itineraryId);
        List<ItineraryItem> targets = itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .sorted(Comparator
                        .comparing((ItineraryItem i) -> parseScheduleMinutes(i.getScheduledTime()),
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparingInt(ItineraryItem::getDisplayOrder))
                .collect(Collectors.toList());
        if (targets.isEmpty()) {
            return itinerary;
        }

        int orderBase = itinerary.getItems().stream()
                .filter(i -> targets.stream().noneMatch(t -> t.getId().equals(i.getId())))
                .mapToInt(ItineraryItem::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).setDisplayOrder(orderBase + i);
        }
        return itineraryRepository.save(itinerary);
    }

    /** "09:00" / "9:00" → 분 단위. 파싱 실패·빈 값이면 null */
    private static Integer parseScheduleMinutes(String scheduledTime) {
        if (scheduledTime == null || scheduledTime.isBlank()) {
            return null;
        }
        String t = scheduledTime.trim();
        String[] parts = t.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private SharedItineraryResponse toShared(Itinerary itinerary) {
        return SharedItineraryResponse.builder()
                .shareToken(itinerary.getShareToken())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .startDate(itinerary.getStartDate() == null ? null : itinerary.getStartDate().toString())
                .endDate(itinerary.getEndDate() == null ? null : itinerary.getEndDate().toString())
                .companionType(itinerary.getCompanionType() == null ? null : itinerary.getCompanionType().name())
                .withPet(itinerary.isWithPet())
                .shareUrlPath("/#/share/" + itinerary.getShareToken())
                .items(itinerary.getItems().stream()
                        .sorted(java.util.Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                        .map(i -> SharedItineraryResponse.SharedItem.builder()
                                .placeName(i.getPlaceName())
                                .scheduledTime(i.getScheduledTime())
                                .visitDate(i.getVisitDate() == null ? null : i.getVisitDate().toString())
                                .thumbnailUrl(i.getThumbnailUrl())
                                .category(i.getCategory())
                                .tags(i.getTags())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
