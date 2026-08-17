package com.windmill.service.recommendation;

import com.windmill.client.KoreanHolidayClient;
import com.windmill.util.KoreaClock;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 공휴일 조회는 data.go.kr 외부 호출(비동기)이 필요한데, BusinessHoursEvaluator는 RouteRecalculationService·
 * Stage2 등 곳곳에서 순수 동기 정적 유틸로 쓰이고 있어 시그니처를 Mono로 바꾸면 파급이 크다.
 * RegionCodeService(기동 시 region-codes.json 로드)와 같은 원칙으로, 앱 기동 시 올해·내년 공휴일을
 * 미리 읽어와 정적 캐시에 담아두고 이후엔 순수 동기 조회만 하도록 한다.
 *
 * 기동 시 1회 로드로는 두 경우를 놓친다: (1) data.go.kr 서비스키가 아직 활용신청 승인 전이면 그
 * 시점의 실패가 재배포 전까지 영구히 남고, (2) 정부가 임시공휴일을 뒤늦게 공고하면(예: 2026-08-17
 * 대체공휴일) 기동 이후 새로 반영된 공휴일을 재배포 없이는 못 받는다. 그래서 기동 시 1회 외에도
 * 주기적으로 재조회한다(2026-08-17 사용자 제보로 실제 SpcdeInfoService 라이브 호출이
 * SERVICE_KEY_IS_NOT_REGISTERED_ERROR로 실패하고 있었음을 확인 - 활용신청 승인 후 재배포 없이
 * 자동 회복되도록 하기 위한 대응).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KoreanHolidayCache {

    private static volatile Map<Integer, Set<LocalDate>> cache = Map.of();

    private final KoreanHolidayClient client;

    @PostConstruct
    void warmUp() {
        refresh();
    }

    /** 올해·내년 공휴일을 재조회해 캐시를 갱신한다. 최초 1회는 warmUp()이 부팅 시 바로 호출하므로
     * 여기선 6시간 뒤부터 6시간마다(중복 즉시실행 방지 - initialDelay). */
    @Scheduled(initialDelay = 6, fixedRate = 6, timeUnit = TimeUnit.HOURS)
    void refresh() {
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
