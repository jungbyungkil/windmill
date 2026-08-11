package com.windmill.service.recommendation;

import com.windmill.dto.Badge;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.util.CrowdCongestionEvaluator;
import com.windmill.util.TriggerThresholds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 추천 카드별 실시간 상태 배지(날씨/혼잡/영업) 조립.
 * 폭염은 TMX(없으면 TMP) 33/35℃ B안. 혼잡은 카테고리 → 평시대비% → 피크상대 폴백.
 */
@Component
public class BadgeAssembler {

    public void attach(List<RecommendationCandidate> candidates, RegionCondition condition) {
        for (RecommendationCandidate c : candidates) {
            c.setBadges(buildBadges(c, condition));
        }
    }

    private List<Badge> buildBadges(RecommendationCandidate c, RegionCondition condition) {
        List<Badge> badges = new ArrayList<>();

        Double heatTemp = condition == null ? null : condition.heatProxyTemp();
        if (heatTemp != null && heatTemp >= TriggerThresholds.HEAT_WARNING_TMX) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.WEATHER)
                    .label(String.format("폭염경보 수준 %.0f℃ · 실내 추천", heatTemp))
                    .severity(Badge.Severity.DANGER)
                    .build());
        } else if (heatTemp != null && heatTemp >= TriggerThresholds.HEAT_ADVISORY_TMX) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.WEATHER)
                    .label(String.format("폭염주의보 수준 %.0f℃ · 실내 추천", heatTemp))
                    .severity(Badge.Severity.WARNING)
                    .build());
        } else if (condition != null && condition.getCurrentPop() != null
                && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.WEATHER)
                    .label(weatherLabel(condition))
                    .severity(Badge.Severity.WARNING)
                    .build());
        }

        String placeName = c.getPlaceName();
        String category = condition == null ? null : condition.getCrowdCategory(placeName);
        Double relative = condition == null ? null : condition.getCrowdRelativePercent(placeName);
        Double rate = c.getCrowdRate() != null ? c.getCrowdRate()
                : (condition == null ? null : condition.getCrowdRate(placeName));
        CrowdCongestionEvaluator.Level crowdLevel =
                CrowdCongestionEvaluator.evaluate(category, relative, rate);
        if (crowdLevel.isUrgent()) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.CONGESTION)
                    .label(relative != null
                            ? String.format("평소 대비 %.0f%% · 매우 붐빔", relative)
                            : "매우 붐빔")
                    .severity(Badge.Severity.DANGER)
                    .build());
        } else if (crowdLevel.isTriggered()) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.CONGESTION)
                    .label(relative != null && relative >= TriggerThresholds.CROWD_RELATIVE_WARNING_PCT
                            ? String.format("평소 대비 %.0f%% · 혼잡", relative)
                            : (category != null ? category + " · 혼잡" : "혼잡 예상"))
                    .severity(Badge.Severity.WARNING)
                    .build());
        }

        if (Boolean.TRUE.equals(c.getBusinessOpen())) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.HOURS)
                    .label("영업중")
                    .severity(Badge.Severity.SUCCESS)
                    .build());
        } else if (Boolean.FALSE.equals(c.getBusinessOpen())) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.HOURS)
                    .label("영업종료 · 휴무일 수 있어요")
                    .severity(Badge.Severity.WARNING)
                    .build());
        }

        return badges;
    }

    private String weatherLabel(RegionCondition condition) {
        String fcstTime = condition.getCurrentPopFcstTime();
        if (fcstTime != null && fcstTime.length() >= 2) {
            String hour = String.valueOf(Integer.parseInt(fcstTime.substring(0, 2)));
            return "비 예보 " + hour + "시";
        }
        return "비 예보";
    }
}
