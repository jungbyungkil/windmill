package com.windmill.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 정기휴무·영업시간 판정은 항상 KST(Asia/Seoul) 기준이어야 한다. Render 배포 컨테이너는
 * 기본 타임존이 UTC라, JVM 기본 타임존을 쓰는 LocalDate.now()/LocalTime.now()는 UTC 15:00~23:59
 * (KST 다음날 00:00~08:59) 구간에서 요일이 하루 밀려 정기휴무 판정이 틀어진다.
 */
public final class KoreaClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KoreaClock() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalTime nowTime() {
        return LocalTime.now(ZONE);
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
