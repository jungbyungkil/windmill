package com.windmill.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 일정 추가/시간 수정 직전 휴무·마감 안내. 저장을 막지 않고 경고만 돌려준다.
 */
@Data
public class PlaceHoursCheckRequest {
    private String contentId;
    private Integer contentTypeId;
    private String placeName;
    /** 이미 카드에 있는 정기휴무 원문. 없으면 contentId로 상세를 조회한다. */
    private String restDateText;
    private String closeTime;
    private String useTimeText;
    /** 도착/방문 시각을 알 때만 마감 임박을 본다. 없으면 생략. */
    private String scheduledTime;
    private LocalDate visitDate;
}
