package com.windmill.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 정기휴무·영업시간 판정은 항상 KST(Asia/Seoul) 기준이어야 한다. Render 배포 컨테이너는
 * 기본 타임존이 UTC라, JVM 기본 타임존을 쓰는 LocalDate.now()/LocalTime.now()는 UTC 15:00~23:59
 * (KST 다음날 00:00~08:59) 구간에서 요일이 하루 밀려 정기휴무 판정이 틀어진다.
 *
 * <p>시점 컬럼({@code LocalDateTime} TIMESTAMP WITHOUT TIME ZONE)은 UTC 벽시계로 저장하고,
 * API로 나갈 때만 KST 오프셋(+09:00)을 붙인다. 오프셋 없이 {@code 2026-09-04T05:25:00}을 보내면
 * 브라우저 {@code Date}가 KST로 읽어 9시간 전으로 표시된다.
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

    /** JVM 타임존과 무관한 지금(UTC 벽시계). 알림 createdAt 등 시점 저장용. */
    public static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** KST 벽시계 → UTC 벽시계. 스케줄러 {@code now}를 TIMESTAMP 컬럼에 넣을 때 쓴다. */
    public static LocalDateTime toUtcWall(LocalDateTime kstDateTime) {
        if (kstDateTime == null) {
            return null;
        }
        return kstDateTime.atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * UTC 벽시계 → 클라이언트용 KST OffsetDateTime.
     * 기존 Render 저장값({@code LocalDateTime.now()} = UTC)과 {@link #utcNow()} 신규 값을 같은 규칙으로 읽는다.
     */
    public static OffsetDateTime toKstOffset(LocalDateTime utcWall) {
        if (utcWall == null) {
            return null;
        }
        return utcWall.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toOffsetDateTime();
    }
}
