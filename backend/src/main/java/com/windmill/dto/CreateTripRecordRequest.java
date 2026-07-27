package com.windmill.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateTripRecordRequest {
    private Long itineraryId;
    private String overallNote;
    private int rerouteCount;
    private List<VisitFeedbackRequest> visitFeedback;
}
