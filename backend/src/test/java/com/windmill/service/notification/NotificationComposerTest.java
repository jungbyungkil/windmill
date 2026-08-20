package com.windmill.service.notification;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationComposerTest {

    private final NotificationComposer composer = new NotificationComposer();

    @Test
    void statusBody_usesFirstTriggerDetail() {
        TriggerResult result = TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .triggerDetails(List.of("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요."))
                .build();

        String body = composer.statusBody(result);

        assertTrue(body.startsWith("비 소식이 있어요."));
        assertTrue(body.contains("대안"));
    }

    @Test
    void statusBody_fallsBackWhenNoDetails() {
        TriggerResult result = TriggerResult.builder().level(TriggerLevel.WARNING).triggerDetails(List.of()).build();

        String body = composer.statusBody(result);

        assertEquals("예정된 일정에 변수가 생겼어요. 지금 확인하고 대안을 살펴보세요.", body);
    }

    @Test
    void periodicBody_normalLevel_isReassurance() {
        TriggerResult result = TriggerResult.builder().level(TriggerLevel.NORMAL).triggerDetails(List.of()).build();

        assertEquals("지금까지 계획대로 잘 진행되고 있어요.", composer.periodicBody(result));
    }

    @Test
    void periodicBody_nonNormalLevel_usesDetail() {
        TriggerResult result = TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .triggerDetails(List.of("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"))
                .build();

        assertEquals("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?", composer.periodicBody(result));
    }

    @Test
    void slotBody_singleCafeItem() {
        ItineraryItem item = ItineraryItem.builder().placeName("스타벅스 속초해변점").category("카페").build();

        assertEquals("스타벅스 속초해변점에서 커피 한 잔 하실 시간이에요.", composer.slotBody(List.of(item)));
    }

    @Test
    void slotBody_singleRestaurantItem() {
        ItineraryItem item = ItineraryItem.builder().placeName("속초회센터").category("맛집").build();

        assertEquals("속초회센터에서 식사하실 시간이에요.", composer.slotBody(List.of(item)));
    }

    @Test
    void slotBody_defaultCategory_isMoveSentence() {
        ItineraryItem item = ItineraryItem.builder().placeName("속초등대전망대").category("관광지").build();

        assertEquals("이제 속초등대전망대로 이동할 시간이에요.", composer.slotBody(List.of(item)));
    }

    @Test
    void slotTitle_multipleItems_usesGenericTitle() {
        ItineraryItem a = ItineraryItem.builder().placeName("A").build();
        ItineraryItem b = ItineraryItem.builder().placeName("B").build();

        assertEquals("다음 일정 시간이에요", composer.slotTitle(List.of(a, b)));
    }

    @Test
    void mergedBody_combinesPeriodicAndSlot() {
        TriggerResult result = TriggerResult.builder().level(TriggerLevel.NORMAL).triggerDetails(List.of()).build();
        ItineraryItem item = ItineraryItem.builder().placeName("속초등대전망대").category("관광지").build();

        String body = composer.mergedBody(result, List.of(item));

        assertTrue(body.contains("지금까지 계획대로"));
        assertTrue(body.contains("속초등대전망대로 이동할 시간이에요"));
    }
}
