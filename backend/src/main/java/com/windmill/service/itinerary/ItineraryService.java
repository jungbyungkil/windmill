package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.SharedItineraryResponse;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    @Transactional
    public Itinerary create(String sessionUuid, CreateItineraryRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null
                || !request.getStartDate().equals(request.getEndDate())) {
            throw new IllegalArgumentException("당일치기만 가능합니다. 여행 날짜는 하루만 선택해 주세요.");
        }
        if (request.getStartDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("여행일은 오늘 이후여야 합니다.");
        }
        RegionCode region = regionCodeService.find(request.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + request.getSignguFullCode()));
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
        if (startDate.isBefore(LocalDate.now())) {
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

    @Transactional
    public Itinerary addItem(Long itineraryId, AddItineraryItemRequest request) {
        Itinerary itinerary = get(itineraryId);
        int nextOrder = itinerary.getItems().size();
        // visitDate 미지정(예: 기존 단일 일자 플로우)이면 여행 시작일로 채운다 - 일자별 페이지 그룹핑 기준
        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : itinerary.getStartDate();
        ItineraryItem item = ItineraryItem.builder()
                .itinerary(itinerary)
                .contentId(request.getContentId())
                .contentTypeId(request.getContentTypeId())
                .placeName(request.getPlaceName())
                .thumbnailUrl(request.getThumbnailUrl())
                .scheduledTime(request.getScheduledTime())
                .tags(request.getTags() == null ? List.of() : request.getTags())
                .crowdRate(request.getCrowdRate())
                .displayOrder(nextOrder)
                .visitDate(visitDate)
                .addr1(request.getAddr1())
                .tel(request.getTel())
                .useFeeText(request.getUseFeeText())
                .isFree(request.getIsFree())
                .restDateText(request.getRestDateText())
                .category(request.getCategory())
                .isAlternate(request.isAlternate())
                .mapX(request.getMapX())
                .mapY(request.getMapY())
                .build();
        itinerary.getItems().add(item);
        return itineraryRepository.save(itinerary);
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
            item.setTags(new java.util.ArrayList<>(request.getTags()));
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
     * 동선 꼬임 자동 재배치 - 해당 일자 항목을 최근접 순서로 재정렬하고,
     * 방문 시각을 09:00 시작 + (체류 75분 + 이동거리 기반)으로 다시 붙인다.
     * 고정(pin) 여부와 관계없이 순서·시각을 갱신한다 (장소 자체는 유지).
     */
    @Transactional
    public Itinerary optimizeRoute(Long itineraryId, LocalDate date) {
        Itinerary itinerary = get(itineraryId);
        List<ItineraryItem> targets = itinerary.getItems().stream()
                .filter(i -> date == null
                        || date.equals(i.getVisitDate())
                        || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                .collect(Collectors.toList());
        if (targets.size() < 2) {
            return itinerary;
        }

        List<ItineraryItem> finalOrder = RouteTangleDetector.optimizeOrder(targets);
        assignOptimizedSchedule(finalOrder);

        int orderBase = itinerary.getItems().stream()
                .filter(i -> targets.stream().noneMatch(t -> t.getId().equals(i.getId())))
                .mapToInt(ItineraryItem::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
        for (int i = 0; i < finalOrder.size(); i++) {
            finalOrder.get(i).setDisplayOrder(orderBase + i);
        }
        return itineraryRepository.save(itinerary);
    }

    /** 09:00부터 체류·이동을 반영해 HH:mm 재배정 (스마트일정과 동일 감각) */
    private void assignOptimizedSchedule(List<ItineraryItem> ordered) {
        final int baseStayMinutes = 75;
        final int minutesPerKm = 12;
        final java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        java.time.LocalTime cursor = java.time.LocalTime.of(9, 0);
        java.time.LocalTime latestStart = java.time.LocalTime.of(20, 0);

        for (int i = 0; i < ordered.size(); i++) {
            ItineraryItem item = ordered.get(i);
            if (cursor.isAfter(latestStart)) {
                cursor = latestStart;
            }
            item.setScheduledTime(cursor.format(fmt));

            int travel = 20;
            if (i + 1 < ordered.size()) {
                ItineraryItem next = ordered.get(i + 1);
                Double km = com.windmill.util.GeoUtils.distanceKmSafe(
                        item.getMapX(), item.getMapY(), next.getMapX(), next.getMapY());
                if (km != null) {
                    travel = (int) Math.ceil(km * minutesPerKm);
                    travel = Math.max(10, Math.min(travel, 90));
                }
            } else {
                travel = 0;
            }
            cursor = cursor.plusMinutes(baseStayMinutes + travel);
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
