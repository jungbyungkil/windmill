package com.windmill.controller;

import com.windmill.domain.PushSubscription;
import com.windmill.dto.PushRegisterRequest;
import com.windmill.repository.PushSubscriptionRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 웹 푸시 FCM 토큰 등록 (brief-web-push-notification.md) */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PushController {

    private final PushSubscriptionRepository pushSubscriptionRepository;

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<Void> register(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestBody PushRegisterRequest request) {
        if (request == null || request.getFcmToken() == null || request.getFcmToken().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        PushSubscription sub = pushSubscriptionRepository.findByFcmToken(request.getFcmToken())
                .orElseGet(PushSubscription::new);
        sub.setSessionUuid(sessionId);
        sub.setFcmToken(request.getFcmToken());
        // 일정 없이 설정에서만 켠 구독은 세션 공통으로 두고, 이후 여행이 생기면 itineraryId만 보강한다.
        // null로 덮어쓰면 스케줄러가 일정 구독을 놓칠 수 있다.
        if (request.getItineraryId() != null) {
            sub.setItineraryId(request.getItineraryId());
        }
        pushSubscriptionRepository.save(sub);
        return ResponseEntity.noContent().build();
    }

    /** 이 기기·세션의 푸시 구독 해제 - 스케줄러가 더 이상 이 토큰으로 보내지 않는다 */
    @DeleteMapping("/register")
    @Transactional
    public ResponseEntity<Void> unregister(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestBody(required = false) PushRegisterRequest request) {
        if (request != null && request.getFcmToken() != null && !request.getFcmToken().isBlank()) {
            pushSubscriptionRepository.deleteByFcmToken(request.getFcmToken());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            pushSubscriptionRepository.deleteBySessionUuid(sessionId);
        }
        return ResponseEntity.noContent().build();
    }
}
