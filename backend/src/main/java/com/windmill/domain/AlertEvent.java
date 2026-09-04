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
