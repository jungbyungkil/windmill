package com.windmill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AddItineraryItemRequest {
    @NotBlank
    private String contentId;
    private Integer contentTypeId;
    @NotBlank
    private String placeName;
    private String thumbnailUrl;
    private String scheduledTime;
    private List<String> tags;
    private Double crowdRate;
}
