package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.repository.ItineraryRepository;
import com.windmill.service.region.RegionCodeService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 일정 CRUD - 회원가입 없이 클라이언트가 매 요청 헤더(X-Session-Id)로 보내는 익명 UUID로만 스코핑한다.
 * JPA는 블로킹이므로 컨트롤러에서 별도 스레드(boundedElastic)로 감싸 호출한다.
 */
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final RegionCodeService regionCodeService;

    @Transactional
    public Itinerary create(String sessionUuid, CreateItineraryRequest request) {
        RegionCode region = regionCodeService.find(request.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + request.getSignguFullCode()));
        Itinerary itinerary = Itinerary.builder()
                .sessionUuid(sessionUuid)
                .signguFullCode(region.getSignguFullCode())
                .regionDisplayName(region.getSidoName() + " " + region.getSignguName())
                .weatherNx(region.getWeatherNx())
                .weatherNy(region.getWeatherNy())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .companionType(request.getCompanionType())
                .withPet(request.isWithPet())
                .build();
        return itineraryRepository.save(itinerary);
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
        return itineraryRepository.save(itinerary);
    }

    @Transactional
    public Itinerary deleteItem(Long itineraryId, Long itemId) {
        Itinerary itinerary = get(itineraryId);
        itinerary.getItems().removeIf(i -> i.getId().equals(itemId));
        return itineraryRepository.save(itinerary);
    }
}
