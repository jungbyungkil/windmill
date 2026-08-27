package com.windmill.service.notification;

import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 알림 문구 조립 - 순풍 북엔드(첫 일정 30분 전 / 마지막 일정 종료)와
 * 주황·빨강 즉시 알림. I/O 없는 순수 문자열 가공.
 */
@Component
public class NotificationComposer {

    public static final String DAY_START_BODY =
            "오늘 일정 시작 30분 전입니다. 순풍이 부니 바람따라 여행해주세요.";
    public static final String DAY_END_BODY =
            "오늘 모든 일정을 마칩니다. 여행 마무리를 남겨주세요.";

    public String statusTitle(TriggerLevel level) {
        return level == TriggerLevel.DANGER ? "🔴 지금 코스를 바꿔야 해요" : "🟠 여행에 변수가 생겼어요";
    }

    /** 비·폭염·혼잡·동선 등 걸린 원인을 모두 본문에 실어, 앱을 열기 전에도 무엇을 바꿔야 하는지 보이게 한다. */
    public String statusBody(TriggerResult result) {
        String core = joinDetails(result, "예정된 일정에 변수가 생겼어요.");
        return core + " 지금 확인하고 대안을 살펴보세요.";
    }

    public String dayStartTitle() {
        return "🟢 오늘 일정 시작 30분 전입니다";
    }

    public String dayStartBody() {
        return DAY_START_BODY;
    }

    public String dayEndTitle() {
        return "🟢 오늘 모든 일정을 마칩니다";
    }

    public String dayEndBody() {
        return DAY_END_BODY;
    }

    private String joinDetails(TriggerResult result, String fallback) {
        List<String> details = result.getTriggerDetails();
        if (details == null || details.isEmpty()) {
            return fallback;
        }
        return String.join(" ", details);
    }
}
