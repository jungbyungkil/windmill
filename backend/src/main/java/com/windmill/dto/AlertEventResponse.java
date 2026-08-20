package com.windmill.dto;

import com.windmill.domain.AlertEvent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 알림 피드 화면용 - 상대시간 포맷은 프론트에서 createdAt 기준으로 계산 */
@Data
@Builder
public class AlertEventResponse {
    private Long id;
    private TriggerLevel level;
    private String icon;
    private String headline;
    private String detail;
    private LocalDateTime createdAt;

    public static AlertEventResponse from(AlertEvent event) {
        return AlertEventResponse.builder()
                .id(event.getId())
                .level(event.getLevel())
                .icon(event.getIcon())
                .headline(event.getHeadline())
                .detail(event.getDetail())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
