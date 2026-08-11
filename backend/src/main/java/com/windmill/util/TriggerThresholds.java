package com.windmill.util;

/**
 * 혼잡·폭염·강수 트리거 공통 임계값.
 * <p>
 * 폭염(B안): 단기예보에 체감온도가 없어 일최고기온(TMX)을 특보 프록시로 사용.
 * 주의보 33℃ / 경보 35℃.
 * <p>
 * 혼잡: API가 카테고리(여유·보통·붐빔…)를 주면 그걸 우선하고,
 * 수치만 있으면 해당 장소 평시(시리즈 평균) 대비 %로 판정.
 * 시리즈가 짧으면 피크=100 상대 집중률(cnctrRate)로 붐빔 구간을 근사한다.
 */
public final class TriggerThresholds {

    /** 강수확률(%) 임계치 */
    public static final double WEATHER_POP_THRESHOLD = 60.0;

    /** 폭염주의보 프록시 — 일최고기온(TMX) ℃ */
    public static final double HEAT_ADVISORY_TMX = 33.0;
    /** 폭염경보 프록시 — 일최고기온(TMX) ℃ */
    public static final double HEAT_WARNING_TMX = 35.0;

    /**
     * @deprecated {@link #HEAT_ADVISORY_TMX} 사용. 하위 호환용 별칭.
     */
    @Deprecated
    public static final double HEAT_TEMP_THRESHOLD = HEAT_ADVISORY_TMX;

    /** 평시 평균 대비 혼잡 주의(%) */
    public static final double CROWD_RELATIVE_WARNING_PCT = 150.0;
    /** 평시 평균 대비 혼잡 긴급(%) */
    public static final double CROWD_RELATIVE_DANGER_PCT = 200.0;

    /**
     * 피크=100 상대 집중률에서 "붐빔" 근사.
     * 카테고리·평시대비를 쓸 수 없을 때 폴백.
     */
    public static final double CROWD_PEAK_RATE_BUSY = 70.0;
    /** 피크 상대 집중률 "매우붐빔" 근사 */
    public static final double CROWD_PEAK_RATE_VERY_BUSY = 90.0;

    /**
     * @deprecated 절대 집중률 90% 단일 임계 — {@link CrowdCongestionEvaluator} 사용.
     */
    @Deprecated
    public static final double CROWD_RATE_THRESHOLD = CROWD_PEAK_RATE_VERY_BUSY;

    private TriggerThresholds() {
    }
}
