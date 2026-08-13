package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * 세션의 FCM 토큰 등록 - 웹 푸시(brief-web-push-notification.md) 발송 대상.
 * itineraryId는 선택(어느 여행에 대한 알림인지, 없으면 세션 공통 알림용).
 */
@Entity
@Table(name = "push_subscription")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionUuid;

    @Column(nullable = false, unique = true, length = 500)
    private String fcmToken;

    private Long itineraryId;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 같은 nudgeId로는 하루 1회만 발송 - "YYYY-MM-DD:nudgeId" 형태로 마지막 발송 키를 기록 */
    @Column(length = 200)
    private String lastSentKey;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
