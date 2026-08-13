package com.windmill.dto;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** GNB "내 여행 관리" 전체 목록용 - 항목 전체 없이 요약 + 상태만 */
@Data
@Builder
public class ItineraryListItemResponse {
    private Long itineraryId;
    private String signguFullCode;
    private String regionDisplayName;
    private LocalDate startDate;
    private CompanionType companionType;
    private boolean withPet;
    private int placeCount;
    private ItineraryStatus status;

    public static ItineraryListItemResponse from(Itinerary itinerary, ItineraryStatus status) {
        return ItineraryListItemResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .startDate(itinerary.getStartDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .placeCount(itinerary.getItems() == null ? 0 : itinerary.getItems().size())
                .status(status)
                .build();
    }
}
