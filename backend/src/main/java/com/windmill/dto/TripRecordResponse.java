package com.windmill.dto;

import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class TripRecordResponse {
    private Long tripId;
    private String sessionUuid;
    private Long itineraryId;
    private String overallNote;
    private String overallRating;
    private int rerouteCount;
    private List<VisitFeedbackResponse> visitFeedback;
    private String completedAt;

    @Data
    @Builder
    public static class VisitFeedbackResponse {
        private Long itemId;
        private String placeName;
        private String rating;
        private String memo;

        static VisitFeedbackResponse from(VisitFeedback vf) {
            return VisitFeedbackResponse.builder()
                    .itemId(vf.getItemId())
                    .placeName(vf.getPlaceName())
                    .rating(vf.getRating().name())
                    .memo(vf.getMemo())
                    .build();
        }
    }

    public static TripRecordResponse from(TripRecord record) {
        return TripRecordResponse.builder()
                .tripId(record.getId())
                .sessionUuid(record.getSessionUuid())
                .itineraryId(record.getItinerary() == null ? null : record.getItinerary().getId())
                .overallNote(record.getOverallNote())
                .overallRating(record.getOverallRating() == null ? null : record.getOverallRating().name())
                .rerouteCount(record.getRerouteCount())
                .visitFeedback(record.getVisitFeedback().stream()
                        .map(VisitFeedbackResponse::from)
                        .collect(Collectors.toList()))
                .completedAt(record.getCompletedAt().toString())
                .build();
    }
}
