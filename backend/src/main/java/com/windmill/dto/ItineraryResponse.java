package com.windmill.dto;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class ItineraryResponse {
    private Long itineraryId;
    private String signguFullCode;
    private String regionDisplayName;
    private String weatherNx;
    private String weatherNy;
    private LocalDate startDate;
    private LocalDate endDate;
    private CompanionType companionType;
    private boolean withPet;
    private List<ItineraryItemResponse> items;

    public static ItineraryResponse from(Itinerary itinerary) {
        return ItineraryResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .weatherNx(itinerary.getWeatherNx())
                .weatherNy(itinerary.getWeatherNy())
                .startDate(itinerary.getStartDate())
                .endDate(itinerary.getEndDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .items(itinerary.getItems().stream()
                        .map(ItineraryItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
