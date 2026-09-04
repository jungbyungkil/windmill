package com.windmill.dto;

import com.windmill.domain.AlertEvent;
import com.windmill.util.KoreaClock;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** 알림 피드 화면용 - 상대시간 포맷은 프론트에서 createdAt(KST 오프셋) 기준으로 계산 */
@Data
@Builder
public class AlertEventResponse {
    private Long id;
    private TriggerLevel level;
    private String icon;
    private String headline;
    private String detail;
    /** KST 오프셋 포함 ISO-8601. 타임존 없는 LocalDateTime을 그대로 보내면 브라우저가 9시간 어긋난다. */
    private OffsetDateTime createdAt;

    public static AlertEventResponse from(AlertEvent event) {
        return AlertEventResponse.builder()
                .id(event.getId())
                .level(event.getLevel())
                .icon(event.getIcon())
                .headline(event.getHeadline())
                .detail(event.getDetail())
                .createdAt(KoreaClock.toKstOffset(event.getCreatedAt()))
                .build();
    }
}
