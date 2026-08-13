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
    /** 종료(ENDED)된 여행만 - 여행 마무리 작성 시 남긴 한 줄 메모(TripRecord.overallNote) */
    private String overallNote;

    public static ItineraryListItemResponse from(Itinerary itinerary, ItineraryStatus status, String overallNote) {
        return ItineraryListItemResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .startDate(itinerary.getStartDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .placeCount(itinerary.getItems() == null ? 0 : itinerary.getItems().size())
                .status(status)
                .overallNote(overallNote)
                .build();
    }
}
