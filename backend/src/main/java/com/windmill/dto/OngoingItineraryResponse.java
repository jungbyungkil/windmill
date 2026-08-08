package com.windmill.dto;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** 메인 화면 "진행 중인 여행" 목록용 - 항목 전체 없이 요약만 */
@Data
@Builder
public class OngoingItineraryResponse {
    private Long itineraryId;
    private String signguFullCode;
    private String regionDisplayName;
    private LocalDate startDate;
    private CompanionType companionType;
    private boolean withPet;
    private int placeCount;

    public static OngoingItineraryResponse from(Itinerary itinerary) {
        return OngoingItineraryResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .startDate(itinerary.getStartDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .placeCount(itinerary.getItems() == null ? 0 : itinerary.getItems().size())
                .build();
    }
}
