package com.windmill.service.trigger;

import com.windmill.util.SimpleTtlCache;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 지역별(signguFullCode) 집중률/기상 원본 데이터 캐시 - 30분 TTL.
 * 예전엔 속초 단일 지역을 스케줄러가 프리페치했지만, 전국 지역 지원으로 확장되며 실제 활성
 * 일정이 있는 지역만 TriggerScheduler.ensureFresh 호출 시점에 lazy하게 채워지도록 바뀌었다.
 */
@Component
public class RegionConditionCache {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final SimpleTtlCache<String, RegionCondition> cache = new SimpleTtlCache<>(TTL);

    public RegionCondition get(String signguFullCode) {
        return cache.get(signguFullCode);
    }

    public void put(String signguFullCode, RegionCondition condition) {
        cache.put(signguFullCode, condition);
    }
}
