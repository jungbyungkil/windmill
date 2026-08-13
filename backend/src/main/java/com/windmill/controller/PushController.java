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
        sub.setItineraryId(request.getItineraryId());
        pushSubscriptionRepository.save(sub);
        return ResponseEntity.noContent().build();
    }
}
