package com.windmill.dto;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** 409 응답 본문 - 같은 날짜에 이미 있는 진행 중인 일정 요약 (모달에서 안내용) */
@Data
@Builder
public class DuplicateItineraryResponse {
    private Long itineraryId;
    private LocalDate startDate;
    private String regionDisplayName;
    private List<String> placeNames;

    public static DuplicateItineraryResponse from(Itinerary itinerary) {
        return DuplicateItineraryResponse.builder()
                .itineraryId(itinerary.getId())
                .startDate(itinerary.getStartDate())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .placeNames(itinerary.getItems().stream()
                        .sorted(Comparator.comparingInt(ItineraryItem::getDisplayOrder))
                        .map(ItineraryItem::getPlaceName)
                        .collect(Collectors.toList()))
                .build();
    }
}
