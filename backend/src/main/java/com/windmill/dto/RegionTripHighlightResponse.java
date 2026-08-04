package com.windmill.dto;

import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 이 지역으로 떠나는 다른 여행자에게 보여줄 "엄지척(GOOD)" 여행 기록 카드.
 * sessionUuid 등 세션 식별 정보는 넣지 않는다 - 다른 세션(=다른 사람)에게 공개되는 응답이기 때문.
 */
@Data
@Builder
public class RegionTripHighlightResponse {
    private String regionDisplayName;
    private String overallNote;
    private List<String> placeNames;
    private String thumbnailUrl;
    private String startDate;
    private String endDate;
    private String companionType;
    private boolean withPet;
    private String completedAt;

    public static RegionTripHighlightResponse from(TripRecord record) {
        List<String> placeNames = record.getItinerary() == null
                ? List.of()
                : record.getItinerary().getItems().stream()
                        .map(ItineraryItem::getPlaceName)
                        .collect(Collectors.toList());
        String thumbnailUrl = record.getItinerary() == null
                ? null
                : record.getItinerary().getItems().stream()
                        .map(ItineraryItem::getThumbnailUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .findFirst()
                        .orElse(null);
        return RegionTripHighlightResponse.builder()
                .regionDisplayName(record.getItinerary() == null ? null : record.getItinerary().getRegionDisplayName())
                .overallNote(record.getOverallNote())
                .placeNames(placeNames)
                .thumbnailUrl(thumbnailUrl)
                .startDate(record.getItinerary() == null || record.getItinerary().getStartDate() == null
                        ? null : record.getItinerary().getStartDate().toString())
                .endDate(record.getItinerary() == null || record.getItinerary().getEndDate() == null
                        ? null : record.getItinerary().getEndDate().toString())
                .companionType(record.getItinerary() == null || record.getItinerary().getCompanionType() == null
                        ? null : record.getItinerary().getCompanionType().name())
                .withPet(record.getItinerary() != null && record.getItinerary().isWithPet())
                .completedAt(record.getCompletedAt().toString())
                .build();
    }
}
