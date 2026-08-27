package com.windmill.service.notification;

import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationComposerTest {

    private final NotificationComposer composer = new NotificationComposer();

    @Test
    void statusBody_joinsAllTriggerDetails() {
        TriggerResult result = TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .triggerDetails(List.of(
                        "비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요.",
                        "혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"))
                .build();

        String body = composer.statusBody(result);

        assertTrue(body.contains("비 소식이 있어요."));
        assertTrue(body.contains("혼잡도가 높아요."));
        assertTrue(body.contains("대안"));
    }

    @Test
    void statusBody_fallsBackWhenNoDetails() {
        TriggerResult result = TriggerResult.builder().level(TriggerLevel.WARNING).triggerDetails(List.of()).build();

        assertEquals("예정된 일정에 변수가 생겼어요. 지금 확인하고 대안을 살펴보세요.", composer.statusBody(result));
    }

    @Test
    void dayStartBody_isSpecifiedCopy() {
        assertEquals("오늘 일정 시작 30분 전입니다. 순풍이 부니 바람따라 여행해주세요.", composer.dayStartBody());
    }

    @Test
    void dayEndBody_isSpecifiedCopy() {
        assertEquals("오늘 모든 일정을 마칩니다. 여행 마무리를 남겨주세요.", composer.dayEndBody());
    }

    @Test
    void statusTitle_dangerVsWarning() {
        assertEquals("🔴 지금 코스를 바꿔야 해요", composer.statusTitle(TriggerLevel.DANGER));
        assertEquals("🟠 여행에 변수가 생겼어요", composer.statusTitle(TriggerLevel.WARNING));
    }
}
