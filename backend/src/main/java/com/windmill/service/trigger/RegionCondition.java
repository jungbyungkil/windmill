package com.windmill.service.trigger;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * 지역 단위 원본 상태 스냅샷.
 * 혼잡: 장소별 오늘 집중률 + (가능 시) 카테고리·평시 대비 %.
 * 기온: TMP(현재 예보)와 TMX(일최고, 폭염 B안 프록시)를 분리.
 */
@Value
@Builder
public class RegionCondition {
    Map<String, Double> crowdRateByPlaceName;
    /** 장소별 혼잡 카테고리(여유/보통/붐빔…). 없으면 키 없음 */
    Map<String, String> crowdCategoryByPlaceName;
    /** 장소별 평시 평균 대비 %(오늘/시리즈평균×100). 없으면 키 없음 */
    Map<String, Double> crowdRelativePercentByPlaceName;
    Double currentPop;
    /** currentPop이 가리키는 예보 시각 - "HHmm" 원문(fcstTime) */
    String currentPopFcstTime;
    /** 가장 가까운 예보의 기온(℃, TMP) */
    Double currentTemp;
    /** 일최고기온(℃, TMX). 폭염 트리거 B안 기준. 없으면 null */
    Double dailyMaxTemp;
    Instant refreshedAt;

    public Double getCrowdRate(String placeName) {
        // Map.of() 등 불변 맵은 get(null)에서 NPE를 던지므로 placeName null도 방어한다.
        return placeName == null || crowdRateByPlaceName == null ? null : crowdRateByPlaceName.get(placeName);
    }

    public String getCrowdCategory(String placeName) {
        return placeName == null || crowdCategoryByPlaceName == null ? null : crowdCategoryByPlaceName.get(placeName);
    }

    public Double getCrowdRelativePercent(String placeName) {
        return placeName == null || crowdRelativePercentByPlaceName == null ? null : crowdRelativePercentByPlaceName.get(placeName);
    }

    /**
     * 폭염 판정용 기온 — TMX 우선, 없으면 TMP.
     */
    public Double heatProxyTemp() {
        if (dailyMaxTemp != null) {
            return dailyMaxTemp;
        }
        return currentTemp;
    }
}
