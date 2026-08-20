package com.windmill.service.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;

/**
 * FCM Admin SDK로 서버 → 클라이언트 웹 푸시 발송(brief-web-push-notification.md).
 * FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 미설정 시 안전하게 no-op(로그만) - OpenAiService의
 * "미설정 시 자동 폴백" 관행과 동일. Firebase 콘솔에서 서비스 계정 키(JSON) 발급 후 base64
 * 인코딩해 이 환경변수에 넣으면 바로 동작한다.
 */
@Slf4j
@Service
public class PushSenderService {

    @Value("${firebase.service-account-json-base64:}")
    private String serviceAccountJsonBase64;

    private volatile boolean initAttempted = false;
    private volatile FirebaseMessaging messaging;

    public boolean isConfigured() {
        return serviceAccountJsonBase64 != null && !serviceAccountJsonBase64.isBlank();
    }

    private synchronized FirebaseMessaging messaging() {
        if (initAttempted) {
            return messaging;
        }
        initAttempted = true;
        if (!isConfigured()) {
            log.info("[Push] FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 미설정 - 푸시 발송 비활성화");
            return null;
        }
        try {
            byte[] json = Base64.getDecoder().decode(serviceAccountJsonBase64);
            GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(json));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            messaging = FirebaseMessaging.getInstance(app);
            log.info("[Push] Firebase Admin 초기화 완료");
        } catch (Exception e) {
            log.warn("[Push] Firebase 초기화 실패 - 푸시 발송 비활성화: {}", e.toString());
            messaging = null;
        }
        return messaging;
    }

    /** @return 발송 성공 여부. 미설정/실패해도 예외를 던지지 않는다(호출부 흐름을 막지 않기 위함) */
    public boolean send(String fcmToken, String title, String body) {
        return send(fcmToken, title, body, Map.of());
    }

    /**
     * data는 웹 푸시 클릭 시 딥링크(itineraryId/url)를 sw.js의 notificationclick 핸들러에 전달하기
     * 위함(NotificationSchedulerService 참고).
     * @return 발송 성공 여부. 미설정/실패해도 예외를 던지지 않는다(호출부 흐름을 막지 않기 위함)
     */
    public boolean send(String fcmToken, String title, String body, Map<String, String> data) {
        FirebaseMessaging fm = messaging();
        if (fm == null || fcmToken == null || fcmToken.isBlank()) {
            return false;
        }
        try {
            Message message = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    .build();
            fm.send(message);
            return true;
        } catch (FirebaseMessagingException e) {
            log.warn("[Push] 발송 실패 token={} : {}", fcmToken, e.toString());
            return false;
        }
    }
}
