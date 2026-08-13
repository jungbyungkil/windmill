package com.windmill.util;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 시간/타임존 계산 지점(영업 여부 KST 재계산, 종료 일정 판정 등)에 breadcrumb을 남긴다.
 * 브리프(Sentry 연동) 요구사항 - 네트워크 오류와 로직 오류를 구분할 컨텍스트(서버 UTC 시각,
 * 계산된 KST 시각)를 이벤트에 함께 남겨 이 클래스의 과거 타임존 버그 재발 시 원인 파악을 돕는다.
 * DSN 미설정이면 Sentry SDK가 자동 비활성화되어 안전하게 no-op된다.
 */
public final class SentryBreadcrumbs {

    private SentryBreadcrumbs() {
    }

    public static void timeCalc(String category, String message) {
        Breadcrumb b = new Breadcrumb();
        b.setCategory("time." + category);
        b.setMessage(message);
        b.setLevel(SentryLevel.INFO);
        b.setData("serverTimeUtc", LocalDateTime.now(ZoneOffset.UTC).toString());
        b.setData("koreaTime", KoreaClock.now().toString());
        Sentry.addBreadcrumb(b);
    }
}
