package com.windmill.dto;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

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
    /** 종료된 여행만 - "여행 기록"(일기) 상세로 바로 이동하기 위한 ID */
    private Long tripRecordId;
    /** 종료된 여행만 - TripRecord.overallRating(GOOD/NEUTRAL/BAD), 없으면 null(미평가) */
    private String overallRating;
    /** 방문 순서(scheduledTime)대로 정렬한 장소명 - 목록 카드에 짧게 코스를 보여주기 위함(전체는 상세 화면에서) */
    private List<String> placeNames;

    public static ItineraryListItemResponse from(Itinerary itinerary, ItineraryStatus status, TripRecord record) {
        List<ItineraryItem> items = itinerary.getItems();
        return ItineraryListItemResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .startDate(itinerary.getStartDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .placeCount(items == null ? 0 : items.size())
                .status(status)
                .overallNote(record == null ? null : record.getOverallNote())
                .tripRecordId(record == null ? null : record.getId())
                .overallRating(record == null || record.getOverallRating() == null ? null : record.getOverallRating().name())
                .placeNames(items == null ? List.of() : items.stream()
                        .sorted(Comparator.comparing(ItineraryItem::getScheduledTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(ItineraryItem::getPlaceName)
                        .toList())
                .build();
    }
}
