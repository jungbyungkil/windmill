package com.windmill.domain;

import com.windmill.dto.TriggerLevel;
import com.windmill.util.KoreaClock;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 실제로 발송된(또는 발송 판정을 거친) 알림 1건의 이력 - "알림" 피드 화면에서 그대로 보여준다.
 * NotificationSchedulerService가 dispatch()를 호출하기로 결정한 시점에만 기록되므로,
 * 사용자가 실제로 받은 푸시 알림과 이 피드 내용이 항상 일치한다.
 */
@Entity
@Table(name = "alert_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itineraryId;

    /** nudgeId 접두사 재사용 - STATUS / DAY_START / DAY_END (필터링 여지용, UI엔 아직 미노출) */
    @Column(nullable = false, length = 20)
    private String kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TriggerLevel level;

    @Column(nullable = false, length = 20)
    private String icon;

    @Column(nullable = false, length = 200)
    private String headline;

    @Column(length = 500)
    private String detail;

    /**
     * 이 알림이 가리키는 대표 장소(카드 CTA용) - {@link com.windmill.service.notification.NotificationSchedulerService}의
     * primaryAffectedItemId와 동일 기준. 구버전 알림(이 필드 도입 전)은 null이라 프론트가 CTA를 숨긴다.
     */
    @Column
    private Long affectedItemId;

    /**
     * affectedItemId 당시의 contentId 스냅샷. 알림 발송 이후 그 슬롯이 다른 장소로 교체되면(같은 itemId를
     * 재사용하지 않고 삭제+새 항목 추가가 일반적이라) 현재 일정에서 이 contentId를 더는 찾을 수 없게 되고,
     * 프론트는 그 알림 카드를 회색 처리 + CTA 숨김 처리한다(2026-09-14 핸드오프 브리프 D5).
     */
    @Column(length = 50)
    private String affectedContentId;

    /** affectedItemId 당시의 장소명 스냅샷 - 슬롯이 삭제된 뒤에도 카드 문구는 그대로 보여주기 위함 */
    @Column(length = 100)
    private String affectedPlaceName;

    /**
     * UTC 벽시계(타임존 없는 TIMESTAMP). Render JVM 기본 TZ가 UTC라 기존 행도 이 의미다.
     * 응답은 {@link com.windmill.util.KoreaClock#toKstOffset}으로 +09:00을 붙인다.
     */
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = KoreaClock.utcNow();
        }
    }
}
