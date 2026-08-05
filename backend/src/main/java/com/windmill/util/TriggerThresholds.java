package com.windmill.util;

/**
 * 실시간 변수(날씨/혼잡도) 트리거·배지 판정에 공통으로 쓰는 임계값.
 * TriggerDetectionService(저장된 일정 항목 기준)와 BadgeAssembler(추천 카드 기준)가 동일 기준을 쓰도록 공유한다.
 */
public final class TriggerThresholds {

    /** 강수확률(%) 임계치 - 이 이상이면 기상 트리거/배지 */
    public static final double WEATHER_POP_THRESHOLD = 60.0;
    /** 집중률(%) 임계치 - 여유율 10% 이하(=집중률 90% 이상)면 혼잡도 트리거/배지 */
    public static final double CROWD_RATE_THRESHOLD = 90.0;

    private TriggerThresholds() {
    }
}
