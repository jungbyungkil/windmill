package com.windmill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
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
    /** 미지정 시 ItineraryService가 itinerary.startDate로 채운다 (기존 단일 일자 흐름과의 하위호환) */
    private LocalDate visitDate;
    private String addr1;
    private String tel;
    private String useFeeText;
    private Boolean isFree;
    private String restDateText;
    private String category;
    private boolean isAlternate;
}
