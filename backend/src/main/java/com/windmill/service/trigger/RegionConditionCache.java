package com.windmill.service.trigger;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TriggerScheduler가 주기적으로 채우는 속초 지역 단위 원본 데이터 캐시.
 * 일정별 백그라운드 잡 대신, 지역 단위로 한 번만 갱신해 모든 일정의 트리거 판정에 재사용한다.
 */
@Getter
@Component
public class RegionConditionCache {

    private final Map<String, Double> crowdRateByPlaceName = new ConcurrentHashMap<>();
    private volatile Double currentPop; // 강수확률(%), 기상청 단기예보
    private volatile Instant lastRefreshedAt;

    public void updateCrowdRates(Map<String, Double> rates) {
        crowdRateByPlaceName.clear();
        crowdRateByPlaceName.putAll(rates);
        lastRefreshedAt = Instant.now();
    }

    public void updatePop(Double pop) {
        this.currentPop = pop;
        lastRefreshedAt = Instant.now();
    }

    public Double getCrowdRate(String placeName) {
        return crowdRateByPlaceName.get(placeName);
    }
}
