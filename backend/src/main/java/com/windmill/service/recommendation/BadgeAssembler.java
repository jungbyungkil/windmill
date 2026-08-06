package com.windmill.service.recommendation;

import com.windmill.dto.Badge;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.util.TriggerThresholds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 추천 카드별 실시간 상태 배지(날씨/혼잡/영업) 조립 - 새 API 호출 없이 파이프라인이 이미 가진 정보만 재사용한다.
 * 혼잡 배지는 절대 임계치(TriggerDetectionService와 동일 기준)뿐 아니라, 이 지역에서 이미 조회된 관광지들의
 * 평균 집중률(RegionCondition.crowdRateByPlaceName) 대비 상대적으로 붐비는 곳도 함께 표시한다 -
 * "행정동 단위 방문자수"는 확보한 API로 지원 불가하다는 조사 결과에 따라, 관광지별 상대 비교로 대체한 것이다.
 */
@Component
public class BadgeAssembler {

    /** 상대 비교 시 "평소보다 혼잡"으로 볼 최소 편차(percentage point) */
    private static final double RELATIVE_CROWD_GAP = 15.0;

    public void attach(List<RecommendationCandidate> candidates, RegionCondition condition) {
        Double regionAverageCrowdRate = averageCrowdRate(condition);
        for (RecommendationCandidate c : candidates) {
            c.setBadges(buildBadges(c, condition, regionAverageCrowdRate));
        }
    }

    private List<Badge> buildBadges(RecommendationCandidate c, RegionCondition condition, Double regionAverage) {
        List<Badge> badges = new ArrayList<>();

        if (condition != null && condition.getCurrentTemp() != null
                && condition.getCurrentTemp() >= TriggerThresholds.HEAT_TEMP_THRESHOLD) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.WEATHER)
                    .label(String.format("폭염 %.0f℃ · 실내 추천", condition.getCurrentTemp()))
                    .severity(Badge.Severity.DANGER)
                    .build());
        } else if (condition != null && condition.getCurrentPop() != null
                && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD) {
            badges.add(Badge.builder()
                    .type(Badge.BadgeType.WEATHER)
                    .label(weatherLabel(condition))
                    .severity(Badge.Severity.WARNING)
                    .build());
        }

        Double crowdRate = c.getCrowdRate();
        if (crowdRate != null) {
            if (crowdRate >= TriggerThresholds.CROWD_RATE_THRESHOLD) {
                badges.add(Badge.builder()
                        .type(Badge.BadgeType.CONGESTION)
                        .label("혼잡 예상")
                        .severity(Badge.Severity.WARNING)
                        .build());
            } else if (regionAverage != null && crowdRate - regionAverage >= RELATIVE_CROWD_GAP) {
                badges.add(Badge.builder()
                        .type(Badge.BadgeType.CONGESTION)
                        .label(String.format("평소보다 혼잡해요 (%.0f%%)", crowdRate))
                        .severity(Badge.Severity.WARNING)
                        .build());
            }
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

    private Double averageCrowdRate(RegionCondition condition) {
        if (condition == null || condition.getCrowdRateByPlaceName() == null
                || condition.getCrowdRateByPlaceName().isEmpty()) {
            return null;
        }
        return condition.getCrowdRateByPlaceName().values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(Double.NaN);
    }
}
