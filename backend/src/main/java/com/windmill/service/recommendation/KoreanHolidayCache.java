package com.windmill.service.recommendation;

import com.windmill.client.KoreanHolidayClient;
import com.windmill.util.KoreaClock;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 공휴일 조회는 data.go.kr 외부 호출(비동기)이 필요한데, BusinessHoursEvaluator는 RouteRecalculationService·
 * Stage2 등 곳곳에서 순수 동기 정적 유틸로 쓰이고 있어 시그니처를 Mono로 바꾸면 파급이 크다.
 * RegionCodeService(기동 시 region-codes.json 로드)와 같은 원칙으로, 앱 기동 시 올해·내년 공휴일을
 * 한 번 블로킹으로 미리 읽어와 정적 캐시에 담아두고 이후엔 순수 동기 조회만 하도록 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KoreanHolidayCache {

    private static volatile Map<Integer, Set<LocalDate>> cache = Map.of();

    private final KoreanHolidayClient client;

    @PostConstruct
    void warmUp() {
        int year = KoreaClock.today().getYear();
        refreshYear(year);
        refreshYear(year + 1);
    }

    private void refreshYear(int year) {
        try {
            Set<LocalDate> holidays = client.getHolidays(year).blockOptional(Duration.ofSeconds(10)).orElse(Set.of());
            Map<Integer, Set<LocalDate>> updated = new HashMap<>(cache);
            updated.put(year, holidays);
            cache = updated;
        } catch (Exception e) {
            log.warn("[KoreanHolidayCache] {}년 공휴일 조회 실패: {}", year, e.toString());
        }
    }

    /** 테스트 전용 - 실제 API 호출 없이 캐시를 직접 세팅한다 */
    static void setForTesting(Map<Integer, Set<LocalDate>> testCache) {
        cache = testCache;
    }

    public static boolean isHoliday(LocalDate date) {
        if (date == null) {
            return false;
        }
        Set<LocalDate> holidays = cache.get(date.getYear());
        return holidays != null && holidays.contains(date);
    }
}
