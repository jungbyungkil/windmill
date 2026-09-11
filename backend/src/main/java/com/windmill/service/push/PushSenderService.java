package com.windmill.service.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
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
     *
     * <p>일부러 {@code .setNotification(...)}을 붙이지 않는다 - "notification 메시지"로 보내면 FCM SDK가
     * (firebase-messaging-compat이 sw.js에서 항상 로드돼 있어) 브라우저 알림 표시·클릭 처리를 통째로
     * 가로채 sw.js가 직접 등록한 push/notificationclick 리스너와 경쟁한다. 그 경로에서 실제 클릭 시
     * 알림의 data가 {@code {FCM_MSG: 원본 메시지 전체}}로 한 단계 더 감싸져 sw.js가 기대하는 평평한
     * {@code data.url}을 못 찾고 항상 "/"로 폴백했다(2026-09-11 사용자 제보 - 알림을 눌러도 해당
     * 일정 화면으로 안 감). "data 메시지"(순수 data, notification 필드 없음)로 보내면 FCM SDK가 자동
     * 표시를 하지 않고 sw.js의 자체 push 핸들러가 전적으로 담당하게 되며, 그 핸들러는 이미
     * data.url/data.itineraryId를 올바르게 읽어 알림을 띄우고 클릭 시 그 URL로 정확히 이동한다.
     *
     * @return 발송 성공 여부. 미설정/실패해도 예외를 던지지 않는다(호출부 흐름을 막지 않기 위함)
     */
    public boolean send(String fcmToken, String title, String body, Map<String, String> data) {
        FirebaseMessaging fm = messaging();
        if (fm == null || fcmToken == null || fcmToken.isBlank()) {
            return false;
        }
        try {
            Map<String, String> payload = new HashMap<>();
            if (data != null) {
                payload.putAll(data);
            }
            if (title != null) {
                payload.put("title", title);
            }
            if (body != null) {
                payload.put("body", body);
            }
            Message.Builder builder = Message.builder()
                    .setToken(fcmToken)
                    .putAllData(payload);
            fm.send(builder.build());
            return true;
        } catch (FirebaseMessagingException e) {
            log.warn("[Push] 발송 실패 token={} : {}", fcmToken, e.toString());
            return false;
        }
    }
}
