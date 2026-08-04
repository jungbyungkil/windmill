package com.windmill.dto;

import com.windmill.domain.VisitRating;
import lombok.Data;

import java.util.List;

@Data
public class CreateTripRecordRequest {
    private Long itineraryId;
    private String overallNote;
    private VisitRating overallRating;
    private int rerouteCount;
    private List<VisitFeedbackRequest> visitFeedback;
}
