package com.windmill.dto;

import lombok.Data;

@Data
public class PushRegisterRequest {
    private String fcmToken;
    /** 선택 - 특정 여행에 대한 알림이면 지정, 없으면 세션 공통 알림용 */
    private Long itineraryId;
}
