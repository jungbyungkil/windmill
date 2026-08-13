package com.windmill.dto;

import com.windmill.domain.TripRecord;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** GNB "여행 기록" 목록용 - 완료한 당일치기 요약 카드 */
@Data
@Builder
public class TripRecordSummaryResponse {

    private static final String[] WEEKDAY_KO = {"월", "화", "수", "목", "금", "토", "일"};

    private Long tripRecordId;
    private Long itineraryId;
    private String regionDisplayName;
    private String scheduledDate;
    private String dayOfWeek;
    /** 여행 마무리 작성 시 남긴 한 줄 메모 - 스킵했으면 null */
    private String summaryText;

    public static TripRecordSummaryResponse from(TripRecord record) {
        LocalDate date = record.getItinerary() == null ? null : record.getItinerary().getStartDate();
        return TripRecordSummaryResponse.builder()
                .tripRecordId(record.getId())
                .itineraryId(record.getItinerary() == null ? null : record.getItinerary().getId())
                .regionDisplayName(record.getItinerary() == null ? null : record.getItinerary().getRegionDisplayName())
                .scheduledDate(date == null ? null : date.toString())
                .dayOfWeek(date == null ? null : WEEKDAY_KO[date.getDayOfWeek().getValue() - 1])
                .summaryText(record.getOverallNote())
                .build();
    }
}
