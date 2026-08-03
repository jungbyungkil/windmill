package com.windmill.service.trigger;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/** 지역 단위 원본 상태 스냅샷 - 집중률(장소명 기준)과 강수확률(POP) */
@Value
@Builder
public class RegionCondition {
    Map<String, Double> crowdRateByPlaceName;
    Double currentPop;
    Instant refreshedAt;

    public Double getCrowdRate(String placeName) {
        return crowdRateByPlaceName.get(placeName);
    }
}
