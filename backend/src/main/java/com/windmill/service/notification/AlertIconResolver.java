package com.windmill.service.notification;

import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;

/**
 * 알림 피드 카드용 아이콘 - TriggerResult의 원인 중 하나를 우선순위로 골라 이모지 하나로 압축한다.
 * PinwheelHero.jsx의 CAUSE_META/levelEmoji와 동일한 매핑을 백엔드에 둬서, 프론트는 API가 내려주는
 * icon을 그대로 렌더링만 하면 된다(프론트 쪽에 세 번째 매핑 사본을 만들지 않기 위함).
 */
public final class AlertIconResolver {

    private AlertIconResolver() {
    }

    public static String resolve(TriggerResult result) {
        if (result == null) {
            return "🟢";
        }
        if (result.isWeatherTrigger()) return "🌧️";
        if (result.isHeatTrigger()) return "🌡️";
        if (result.isCrowdTrigger()) return "👥";
        if (result.isClosedDayTrigger()) return "🚫";
        if (result.isHoursEndedTrigger()) return "🕐";
        if (result.isRouteTangleTrigger()) return "🔀";
        if (result.isTravelTimeTrigger()) return "🚗";
        return levelEmoji(result.getLevel());
    }

    private static String levelEmoji(TriggerLevel level) {
        if (level == TriggerLevel.WARNING) return "🟡";
        if (level == TriggerLevel.DANGER) return "🔴";
        return "🟢";
    }
}
