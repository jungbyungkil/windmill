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
    /** CTA("대체 장소 고르기") 대상 슬롯 - null이면 구버전 알림이라 프론트가 버튼을 숨긴다 */
    private Long affectedItemId;
    /** 알림 당시 그 슬롯의 contentId 스냅샷 - 지금 일정에 이 contentId가 없으면 슬롯이 교체된 것 */
    private String affectedContentId;
    private String affectedPlaceName;

    public static AlertEventResponse from(AlertEvent event) {
        return AlertEventResponse.builder()
                .id(event.getId())
                .level(event.getLevel())
                .icon(event.getIcon())
                .headline(event.getHeadline())
                .detail(event.getDetail())
                .createdAt(KoreaClock.toKstOffset(event.getCreatedAt()))
                .affectedItemId(event.getAffectedItemId())
                .affectedContentId(event.getAffectedContentId())
                .affectedPlaceName(event.getAffectedPlaceName())
                .build();
    }
}
