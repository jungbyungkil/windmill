package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.ItineraryListItemResponse;
import com.windmill.dto.ItineraryStatus;
import com.windmill.dto.RegionCode;
import com.windmill.dto.SharedItineraryResponse;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.exception.DuplicateActiveItineraryException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.GeoUtils;
import com.windmill.util.KoreaClock;
import com.windmill.util.PlaceTagSanitizer;
import com.windmill.util.VisitOrderOptimizer;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
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
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;
    private final RouteRecalculationService routeRecalculationService;

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
                            record == null ? null : record.getOverallNote());
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
        int nextOrder = itinerary.getItems().size();
        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : itinerary.getStartDate();
        assertClosingGate(itinerary, request, visitDate);
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                request.getTags(), request.getContentTypeId(), request.getPlaceName(), request.getCategory());
        ItineraryItem item = ItineraryItem.builder()
                .itinerary(itinerary)
                .contentId(request.getContentId())
                .contentTypeId(request.getContentTypeId())
                .placeName(request.getPlaceName())
                .thumbnailUrl(request.getThumbnailUrl())
                .scheduledTime(request.getScheduledTime())
                .tags(tags)
                .crowdRate(request.getCrowdRate())
                .displayOrder(nextOrder)
                .visitDate(visitDate)
                .addr1(request.getAddr1())
                .tel(request.getTel())
                .useFeeText(request.getUseFeeText())
                .isFree(request.getIsFree())
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
                .build();
        itinerary.getItems().add(item);
        return itineraryRepository.save(itinerary);
    }

    /**
     * close 시간이 있으면 도착 예상(또는 지정 scheduledTime)이 마감 임박 이내인지 검사.
     * 이동시간은 TarRlte에 없어 Haversine 추정(또는 기본 20분)을 사용한다.
     */
    private void assertClosingGate(Itinerary itinerary, AddItineraryItemRequest request, LocalDate visitDate) {
        LocalTime close = ClosingTimeGate.parseHhMm(request.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(request.getUseTimeText());
        }
        if (close == null) {
            return;
        }
        LocalTime arrival = ClosingTimeGate.parseHhMm(request.getScheduledTime());
        if (arrival == null) {
            arrival = estimateArrivalTime(itinerary, visitDate, request.getMapX(), request.getMapY());
        }
        ClosingTimeGate.CheckResult check = ClosingTimeGate.check(close, arrival);
        if (check.blocked()) {
            throw new IllegalArgumentException(check.message());
        }
    }

    private LocalTime estimateArrivalTime(Itinerary itinerary, LocalDate visitDate, String mapX, String mapY) {
        List<ItineraryItem> dayItems = itinerary.getItems().stream()
                .filter(i -> visitDate == null
                        || visitDate.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && visitDate.equals(itinerary.getStartDate())))
                .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                .toList();
        LocalTime cursor;
        ItineraryItem last = dayItems.isEmpty() ? null : dayItems.get(dayItems.size() - 1);
        if (last != null && last.getScheduledTime() != null) {
            LocalTime lastStart = ClosingTimeGate.parseHhMm(last.getScheduledTime());
            cursor = lastStart != null ? lastStart.plusMinutes(75) : LocalTime.of(9, 0);
        } else if (visitDate != null && visitDate.equals(KoreaClock.today())) {
            LocalTime soon = KoreaClock.nowTime().plusMinutes(30).withSecond(0).withNano(0);
            int m = soon.getMinute();
            if (m == 0) {
                cursor = soon;
            } else if (m <= 30) {
                cursor = soon.withMinute(30);
            } else {
                cursor = soon.plusHours(1).withMinute(0);
            }
            if (cursor.isBefore(LocalTime.of(9, 0))) {
                cursor = LocalTime.of(9, 0);
            }
        } else {
            cursor = LocalTime.of(9, 0);
        }
        int travel = 20;
        if (last != null && last.getMapX() != null && mapX != null) {
            Double km = GeoUtils.distanceKmSafe(last.getMapX(), last.getMapY(), mapX, mapY);
            if (km != null) {
                travel = (int) Math.ceil(km * 12.0);
                travel = Math.max(10, Math.min(travel, 90));
            }
        }
        return cursor.plusMinutes(travel);
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
        }
        if (request.getRestDateText() != null) {
            item.setRestDateText(request.getRestDateText().isBlank() ? null : request.getRestDateText().trim());
        }
        if (request.getCategory() != null) {
            item.setCategory(request.getCategory().isBlank() ? null : request.getCategory().trim());
        }
        return itineraryRepository.save(itinerary);
    }

    @Transactional
    public Itinerary deleteItem(Long itineraryId, Long itemId) {
        Itinerary itinerary = get(itineraryId);
        itinerary.getItems().removeIf(i -> i.getId().equals(itemId));
        return itineraryRepository.save(itinerary);
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
        Itinerary itinerary = get(itineraryId);
        List<ItineraryItem> targets = itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .collect(Collectors.toList());
        if (targets.size() < 2) {
            return new OptimizeRouteResult(itinerary, null, null);
        }

        RouteRecalculationService.Result recalc =
                routeRecalculationService.recalculate(targets, originLon, originLat);
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
