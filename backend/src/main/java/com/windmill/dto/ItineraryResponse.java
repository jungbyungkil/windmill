package com.windmill.dto;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
    /** 확정된 날짜 목록 - 프론트 일자별 페이지 탭에서 체크표시/다음날 이동 가능 여부 판단에 사용 */
    private Set<LocalDate> confirmedDates;

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
                        .sorted(java.util.Comparator
                                .comparingInt(com.windmill.domain.ItineraryItem::getDisplayOrder)
                                .thenComparing(i -> i.getScheduledTime() == null ? "" : i.getScheduledTime()))
                        .map(ItineraryItemResponse::from)
                        .collect(Collectors.toList()))
                .confirmedDates(itinerary.getConfirmedDates())
                .build();
    }
}
