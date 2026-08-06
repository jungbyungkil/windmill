package com.windmill.service.trigger;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/** 지역 단위 원본 상태 스냅샷 - 집중률(장소명 기준), 강수확률(POP), 기온(TMP) */
@Value
@Builder
public class RegionCondition {
    Map<String, Double> crowdRateByPlaceName;
    Double currentPop;
    /** currentPop이 가리키는 예보 시각 - "HHmm" 원문(fcstTime), 예: "1400". 없으면 null */
    String currentPopFcstTime;
    /** 가장 가까운 예보 시각의 기온(℃, TMP). 없으면 null */
    Double currentTemp;
    Instant refreshedAt;

    public Double getCrowdRate(String placeName) {
        return crowdRateByPlaceName == null ? null : crowdRateByPlaceName.get(placeName);
    }
}
