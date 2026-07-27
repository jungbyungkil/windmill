package com.windmill.dto;

import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class ItineraryResponse {
    private Long itineraryId;
    private String destination;
    private List<ItineraryItemResponse> items;

    public static ItineraryResponse from(Itinerary itinerary) {
        return ItineraryResponse.builder()
                .itineraryId(itinerary.getId())
                .destination(itinerary.getDestination())
                .items(itinerary.getItems().stream()
                        .map(ItineraryItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
