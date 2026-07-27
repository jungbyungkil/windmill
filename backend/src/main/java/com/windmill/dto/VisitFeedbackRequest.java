package com.windmill.dto;

import com.windmill.domain.VisitRating;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VisitFeedbackRequest {
    @NotNull
    private Long itemId;
    private String placeName;
    @NotNull
    private VisitRating rating;
    private String memo;
}
