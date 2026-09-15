package com.windmill.domain;

import com.windmill.util.KoreaClock;
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

    /** 같은 nudgeId로는 하루 1회만 발송 - "YYYY-MM-DD:nudgeId" 형태로 마지막 발송 키를 기록.
     *  STATUS/DAY_START/DAY_END 채널 전용 - 아래 lastProposalSentKey와 필드를 분리했다. */
    @Column(length = 200)
    private String lastSentKey;

    /** 긴급 제안(PROPOSAL:*) 전용 마지막 발송 키 - lastSentKey와 같은 필드를 썼다면 한 틱에서
     *  STATUS 푸시 다음에 PROPOSAL 푸시가 또 나갈 때 STATUS의 dedup 마커를 덮어써 버렸다
     *  (2026-09-15 코드 리뷰에서 발견). */
    @Column(length = 200)
    private String lastProposalSentKey;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = KoreaClock.utcNow(); // 2026-09-14 브리프 Phase 1 전수 검색 - KST JVM 로컬환경 대비
        }
    }
}
