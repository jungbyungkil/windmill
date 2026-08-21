package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.ItineraryListItemResponse;
import com.windmill.dto.ItineraryStatus;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.SharedItineraryResponse;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.exception.DuplicateActiveItineraryException;
import com.windmill.exception.TimeSlotConflictException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.GeoUtils;
import com.windmill.util.KoreaClock;
import com.windmill.util.PlaceTagSanitizer;
import com.windmill.util.TimeConflictGate;
import com.windmill.util.VisitOrderOptimizer;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
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

    private static final int DEFAULT_STAY_MINUTES = 75;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ItineraryRepository itineraryRepository;
    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;
    private final RouteRecalculationService routeRecalculationService;
    private final TourAttractionService tourAttractionService;
    private final RecommendationPipeline recommendationPipeline;

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

    @Transactional
    public Itinerary addItem(Long itineraryId, AddItineraryItemRequest request) {
        Itinerary itinerary = get(itineraryId);
        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : itinerary.getStartDate();
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                request.getTags(), request.getContentTypeId(), request.getPlaceName(), request.getCategory());

        String scheduledTime = request.getScheduledTime();
        List<ItineraryItem> dayItems = null;
        Integer insertBeforeDayIndex = null;

        if (scheduledTime == null) {
            LocalTime close = ClosingTimeGate.parseHhMm(request.getCloseTime());
            if (close == null) {
                close = BusinessHoursEvaluator.extractCloseTimeFromText(request.getUseTimeText());
            }
            if (close != null) {
                LocalTime endArrival = estimateArrivalTime(itinerary, visitDate, request.getMapX(), request.getMapY());
                ClosingTimeGate.CheckResult endCheck = ClosingTimeGate.check(close, endArrival);
                if (!endCheck.allowed()) {
                    // 하루 맨 끝에는 마감 때문에 못 붙는다 - 마감을 안 넘기는 자리 중 가장 늦은(=기존
                    // 앞쪽 일정을 최대한 안 건드리는) 위치를 찾아 그 자리에 끼워 넣는다. 못 찾으면
                    // 기존처럼 차단.
                    dayItems = dayItemsSorted(itinerary, visitDate);
                    InsertionPlan plan = dayItems.isEmpty() ? null
                            : findFeasibleInsertion(dayItems, visitDate, request.getMapX(), request.getMapY(), close);
                    if (plan == null) {
                        throw new IllegalArgumentException(endCheck.message());
                    }
                    insertBeforeDayIndex = plan.index();
                    scheduledTime = plan.arrival().format(TIME_FMT);
                }
            }
        } else {
            // 시간 겹침(다른 일정과의 충돌)을 마감시간 게이트보다 먼저·독립적으로 검사한다.
            // 마감시간 게이트만 보면 "17시에 이미 다른 일정이 있어서" 못 넣는 경우에도
            // 엉뚱하게 "이 장소 자체가 마감 임박" 메시지가 뜨는 문제가 있었다.
            assertNoTimeConflict(itinerary, visitDate, scheduledTime, null);
            assertClosingGate(itinerary, request, visitDate, scheduledTime);
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
        return itineraryRepository.save(itinerary);
    }

    /**
     * 같은 날 이미 예정된 다른 일정과 시간대가 겹치는지 검사(마감시간 게이트와 독립적인 원인).
     * excludeItemId는 기존 항목의 시간을 수정할 때 자기 자신을 겹침 대상에서 빼기 위함(신규 추가 시엔 null).
     */
    private void assertNoTimeConflict(Itinerary itinerary, LocalDate visitDate, String scheduledTime,
                                       Long excludeItemId) {
        LocalTime start = ClosingTimeGate.parseHhMm(scheduledTime);
        if (start == null) {
            return;
        }
        List<TimeConflictGate.Occupant> occupants = dayItemsSorted(itinerary, visitDate).stream()
                .map(i -> new TimeConflictGate.Occupant(
                        i.getId(), i.getPlaceName(), ClosingTimeGate.parseHhMm(i.getScheduledTime())))
                .toList();
        TimeConflictGate.CheckResult result = TimeConflictGate.check(start, occupants, excludeItemId);
        if (result.blocked()) {
            throw new TimeSlotConflictException(result.message(), result.conflictingItemId(),
                    result.conflictingPlaceName(), result.conflictingTime());
        }
    }

    /**
     * close 시간이 있으면 도착 예상(또는 지정 scheduledTime)이 마감 임박 이내인지 검사.
     * 이동시간은 TarRlte에 없어 Haversine 추정(또는 기본 20분)을 사용한다.
     */
    private void assertClosingGate(Itinerary itinerary, AddItineraryItemRequest request, LocalDate visitDate,
                                    String scheduledTime) {
        LocalTime close = ClosingTimeGate.parseHhMm(request.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(request.getUseTimeText());
        }
        if (close == null) {
            return;
        }
        LocalTime arrival = ClosingTimeGate.parseHhMm(scheduledTime);
        if (arrival == null) {
            arrival = estimateArrivalTime(itinerary, visitDate, request.getMapX(), request.getMapY());
        }
        ClosingTimeGate.CheckResult check = ClosingTimeGate.check(close, arrival);
        if (check.blocked()) {
            throw new IllegalArgumentException(check.message());
        }
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
            cursor = lastStart != null ? lastStart.plusMinutes(DEFAULT_STAY_MINUTES) : LocalTime.of(9, 0);
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

    /** Haversine 기반 이동시간 추정(km당 12분, 10~90분 클램프) - 마감 게이트 추정 전용, TarRlte에 이동시간 필드가 없어서 씀 */
    private int travelMinutes(String mapX1, String mapY1, String mapX2, String mapY2) {
        if (mapX1 == null || mapY1 == null || mapX2 == null || mapY2 == null) {
            return 20;
        }
        Double km = GeoUtils.distanceKmSafe(mapX1, mapY1, mapX2, mapY2);
        if (km == null) {
            return 20;
        }
        return Math.max(10, Math.min((int) Math.ceil(km * 12.0), 90));
    }

    private record InsertionPlan(int index, LocalTime arrival) {
    }

    /**
     * 하루 맨 끝은 마감 때문에 막혔을 때, 마감을 넘기지 않는 자리 중 가장 늦은 위치(day-local index,
     * 그 인덱스 "앞"에 끼워 넣는다는 뜻)를 뒤에서부터 찾는다 - 앞쪽 일정을 최대한 안 건드리기 위함.
     * 못 찾으면 null(어디에도 못 들어감 - 기존처럼 차단해야 함).
     */
    private InsertionPlan findFeasibleInsertion(List<ItineraryItem> dayItems, LocalDate visitDate,
                                                  String mapX, String mapY, LocalTime close) {
        for (int p = dayItems.size() - 1; p >= 0; p--) {
            ItineraryItem predecessor = p == 0 ? null : dayItems.get(p - 1);
            LocalTime prevEnd = predecessor == null
                    ? resolveDayStart(visitDate)
                    : parseOrDefault(predecessor.getScheduledTime()).plusMinutes(DEFAULT_STAY_MINUTES);
            int travelToNew = travelMinutes(
                    predecessor == null ? null : predecessor.getMapX(),
                    predecessor == null ? null : predecessor.getMapY(),
                    mapX, mapY);
            LocalTime arrival = prevEnd.plusMinutes(travelToNew);
            if (ClosingTimeGate.check(close, arrival).allowed()) {
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
     * index 위치(그 자리에 있던 항목부터) 뒤로 밀며 표시순서·시각을 다시 계산한다(같은 Haversine
     * 추정 공식). 실제 도로 이동시간까지 반영하려면 사용자가 별도로 "동선 재계산"을 돌려야 한다.
     */
    private void reflowInsert(List<ItineraryItem> dayItems, int index, ItineraryItem newItem, LocalTime start) {
        for (int i = 0; i < index; i++) {
            dayItems.get(i).setDisplayOrder(i);
        }
        newItem.setDisplayOrder(index);
        LocalTime cursor = start;
        ItineraryItem prev = newItem;
        for (int i = index; i < dayItems.size(); i++) {
            ItineraryItem next = dayItems.get(i);
            int travel = travelMinutes(prev.getMapX(), prev.getMapY(), next.getMapX(), next.getMapY());
            cursor = cursor.plusMinutes(DEFAULT_STAY_MINUTES + travel);
            next.setScheduledTime(cursor.format(TIME_FMT));
            next.setDisplayOrder(i + 1);
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
            assertNoTimeConflict(itinerary, targetVisitDate, request.getScheduledTime(), item.getId());
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
        return itineraryRepository.save(itinerary);
    }

    /**
     * 삭제 - 예비 후보(backupContentId, 슬롯 생성 시점에 담아둔 대표 다음으로 가까웠던 후보)가 있으면
     * 그 자리에 자동으로 대체를 시도한다. 예비 후보의 유효성은 삭제 시점에 다시 확인한다(생성 이후
     * 마감/휴무가 바뀌었을 수 있어 스냅샷을 그대로 믿지 않음) - 무효하면 파이프라인 재조회로 폴백,
     * 그마저 없으면 빈 자리로 둔다(대체 실패를 조용히 허용 - 무리하게 아무거나 채우지 않음).
     */
    @Transactional
    public DeleteItemResult deleteItem(Long itineraryId, Long itemId) {
        Itinerary itinerary = get(itineraryId);
        ItineraryItem removed = itinerary.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElse(null);
        if (removed == null) {
            return new DeleteItemResult(itineraryRepository.save(itinerary), null);
        }
        String backupContentId = removed.getBackupContentId();
        Integer backupContentTypeId = removed.getBackupContentTypeId();
        String scheduledTime = removed.getScheduledTime();
        LocalDate visitDate = removed.getVisitDate();
        int displayOrder = removed.getDisplayOrder();
        itinerary.getItems().removeIf(i -> i.getId().equals(itemId));

        ItineraryItem replacement = null;
        if (backupContentId != null && backupContentTypeId != null) {
            replacement = tryBackupReplacement(itinerary, backupContentId, backupContentTypeId,
                    scheduledTime, visitDate, displayOrder);
        }
        if (replacement == null) {
            replacement = tryFallbackReplacement(itinerary, scheduledTime, visitDate, displayOrder);
        }
        if (replacement != null) {
            replacement.setItinerary(itinerary);
            itinerary.getItems().add(replacement);
        }
        Itinerary saved = itineraryRepository.save(itinerary);
        return new DeleteItemResult(saved, replacement == null ? null : replacement.getPlaceName());
    }

    public record DeleteItemResult(Itinerary itinerary, String autoReplacedPlaceName) {
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

    /** 예비 후보가 없거나 무효화됐을 때 - 같은 지역 파이프라인을 한 번 더 돌려 최상위 후보 하나를 대신 담는다 */
    private ItineraryItem tryFallbackReplacement(Itinerary itinerary, String scheduledTime, LocalDate visitDate,
                                                  int displayOrder) {
        List<ItineraryItem> dayItems = dayItemsSorted(itinerary, visitDate);
        ItineraryItem origin = dayItems.isEmpty() ? null : dayItems.get(dayItems.size() - 1);
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(ItineraryItem::getContentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        RecommendationRequest request = RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
                .excludeContentIds(excludeContentIds)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .skipLlm(true)
                .build();

        List<RecommendationCandidate> candidates;
        try {
            candidates = recommendationPipeline.recommend(request).block();
        } catch (Exception e) {
            log.warn("[deleteItem] 대체 후보 재조회 실패 itineraryId={}", itinerary.getId(), e);
            return null;
        }
        if (candidates == null) {
            return null;
        }
        for (RecommendationCandidate c : candidates) {
            LocalTime close = ClosingTimeGate.parseHhMm(c.getCloseTime());
            if (close == null) {
                close = BusinessHoursEvaluator.extractCloseTimeFromText(c.getUseTimeText());
            }
            if (!isSlotStillValid(itinerary, visitDate, scheduledTime, close, null)) {
                continue;
            }
            return ItineraryItem.builder()
                    .contentId(c.getContentId())
                    .contentTypeId(c.getContentTypeId())
                    .placeName(c.getPlaceName())
                    .thumbnailUrl(c.getThumbnailUrl())
                    .mapX(c.getMapX())
                    .mapY(c.getMapY())
                    .scheduledTime(scheduledTime)
                    .visitDate(visitDate)
                    .addr1(c.getAddr1())
                    .tel(c.getTel())
                    .useFeeText(c.getUseFeeText())
                    .isFree(c.getIsFree())
                    .estimatedCostPerPerson(c.getEstimatedCostPerPerson())
                    .restDateText(c.getRestDateText())
                    .closeTime(c.getCloseTime())
                    .useTimeText(c.getUseTimeText())
                    .homepageUrl(c.getHomepageUrl())
                    .category(c.getCategory())
                    .strollerFriendly(c.getStrollerFriendly())
                    .accessibleFriendly(c.isAccessibleFriendly())
                    .crowdRate(c.getCrowdRate())
                    .displayOrder(displayOrder)
                    .isAlternate(true)
                    .build();
        }
        return null;
    }

    /** 마감시간·시간겹침 둘 다 지금 시점 기준으로 통과해야 대체를 실행한다 */
    private boolean isSlotStillValid(Itinerary itinerary, LocalDate visitDate, String scheduledTime,
                                      LocalTime close, Long excludeItemId) {
        LocalTime arrival = ClosingTimeGate.parseHhMm(scheduledTime);
        if (arrival == null) {
            return true;
        }
        if (close != null && ClosingTimeGate.check(close, arrival).blocked()) {
            return false;
        }
        List<TimeConflictGate.Occupant> occupants = dayItemsSorted(itinerary, visitDate).stream()
                .map(i -> new TimeConflictGate.Occupant(
                        i.getId(), i.getPlaceName(), ClosingTimeGate.parseHhMm(i.getScheduledTime())))
                .toList();
        return !TimeConflictGate.check(arrival, occupants, excludeItemId).blocked();
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
        List<ItineraryItem> targets = itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .collect(Collectors.toList());
        if (targets.size() < 2) {
            return new OptimizeRouteResult(itinerary, null, null);
        }

        LocalTime overrideStartTime = ClosingTimeGate.parseHhMm(startTime);
        RouteRecalculationService.Result recalc =
                routeRecalculationService.recalculate(targets, originLon, originLat, overrideStartTime);
        List<ItineraryItem> finalOrder = recalc.ordered();

        int orderBase = itinerary.getItems().stream()
                .filter(i -> targets.stream().noneMatch(t -> t.getId().equals(i.getId())))
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
        return new OptimizeRouteResult(saved, recalc.message(), km);
    }

    /** 하위 호환 */
    @Transactional
    public Itinerary optimizeRoute(Long itineraryId, LocalDate date) {
        return optimizeRoute(itineraryId, date, null, null).itinerary();
    }

    public record OptimizeRouteResult(Itinerary itinerary, String message, Double totalDistanceKm) {
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
