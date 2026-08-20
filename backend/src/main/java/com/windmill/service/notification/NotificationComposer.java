package com.windmill.service.notification;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 알림 3종(정기/슬롯/상태악화)의 제목·본문 조립 - 순수 문자열 가공만 하는 컴포넌트(I/O 없음).
 * 상태악화 알림 본문은 TriggerResult.triggerDetails(이미 사람이 읽는 한국어 문장으로 채워짐,
 * TriggerDetectionService 참고)를 그대로 재사용해 라벨 매핑을 새로 만들지 않는다.
 */
@Component
public class NotificationComposer {

    public String statusTitle(TriggerLevel level) {
        return level == TriggerLevel.DANGER ? "🔴 지금 코스를 바꿔야 해요" : "🟠 여행에 변수가 생겼어요";
    }

    public String statusBody(TriggerResult result) {
        return firstDetail(result, "예정된 일정에 변수가 생겼어요.") + " 지금 확인하고 대안을 살펴보세요.";
    }

    public String periodicTitle() {
        return "🟢 바람따라가 지켜보고 있어요";
    }

    public String periodicBody(TriggerResult result) {
        if (result.getLevel() == TriggerLevel.NORMAL) {
            return "지금까지 계획대로 잘 진행되고 있어요.";
        }
        // NORMAL이 아닌데도 정기 틱이 걸린 경우 - 상태악화 알림이 이미 따로 나갔거나 레벨이 그대로
        // 유지 중인 상황. 현재 상황을 그대로 요약해서 반복 안내한다.
        return firstDetail(result, "지금 상황을 확인해보세요.");
    }

    public String slotTitle(List<ItineraryItem> items) {
        return items.size() == 1 ? items.get(0).getPlaceName() + " 시간이에요" : "다음 일정 시간이에요";
    }

    public String slotBody(List<ItineraryItem> items) {
        return items.stream().map(this::slotSentence).collect(Collectors.joining(" "));
    }

    public String mergedTitle() {
        return "🟢 지금 상황과 다음 일정을 확인하세요";
    }

    public String mergedBody(TriggerResult result, List<ItineraryItem> items) {
        return periodicBody(result) + " " + slotBody(items);
    }

    private String slotSentence(ItineraryItem item) {
        String name = item.getPlaceName();
        String category = item.getCategory();
        if (category != null && category.contains("카페")) {
            return name + "에서 커피 한 잔 하실 시간이에요.";
        }
        if (category != null && category.contains("맛집")) {
            return name + "에서 식사하실 시간이에요.";
        }
        return "이제 " + name + "로 이동할 시간이에요.";
    }

    private String firstDetail(TriggerResult result, String fallback) {
        List<String> details = result.getTriggerDetails();
        return details != null && !details.isEmpty() ? details.get(0) : fallback;
    }
}
