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
    /** "HH:mm" 영업 종료 스냅샷 */
    private String closeTime;
    private String useTimeText;
    private String homepageUrl;
    private String category;
    /** boolean(원시타입)이면 Lombok의 isXxx() getter를 Jackson이 "alternate"로 인식해 JSON 키
     *  "isAlternate"를 조용히 무시한다(isPinned와 동일 문제) - Boolean(래퍼)로 회피 */
    private Boolean isAlternate;
    private String mapX;
    private String mapY;
}
