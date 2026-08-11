package com.windmill.util;

import com.windmill.service.recommendation.BusinessHoursEvaluator;

import java.time.LocalTime;

/**
 * 장소 선택 시 영업 종료(close) 반영.
 * 도착 예상 시각이 마감 임박 버퍼(기본 1시간) 이후면 선택 불가.
 * TarRlte에는 이동시간 필드가 없어, 호출측이 추정 도착 시각을 넘긴다.
 */
public final class ClosingTimeGate {

    private ClosingTimeGate() {
    }

    public static CheckResult check(LocalTime closeTime, LocalTime estimatedArrival) {
        return check(closeTime, estimatedArrival, BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES);
    }

    public static CheckResult check(LocalTime closeTime, LocalTime estimatedArrival, int bufferMinutes) {
        if (closeTime == null || estimatedArrival == null) {
            return CheckResult.allowed(estimatedArrival, null, null);
        }
        int buf = Math.max(0, bufferMinutes);
        LocalTime latestArrivalBy = closeTime.minusMinutes(buf);
        // 도착 >= 마감-버퍼 → 마감 임박/마감으로 선택 불가
        if (!estimatedArrival.isBefore(latestArrivalBy)) {
            String message = String.format(
                    "이 장소는 %s에 마감되어 선택할 수 없습니다. %s까지는 도착하셔야 합니다.",
                    formatFriendly(closeTime),
                    formatFriendly(latestArrivalBy));
            return new CheckResult(false, true, closeTime, latestArrivalBy, estimatedArrival, message);
        }
        return CheckResult.allowed(estimatedArrival, closeTime, latestArrivalBy);
    }

    /** "HH:mm" / LocalTime 파싱. 실패 시 null */
    public static LocalTime parseHhMm(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        String t = hhmm.trim();
        String[] parts = t.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return LocalTime.of(h, m);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatFriendly(LocalTime time) {
        if (time.getMinute() == 0) {
            return time.getHour() + "시";
        }
        return time.getHour() + "시 " + time.getMinute() + "분";
    }

    public record CheckResult(
            boolean allowed,
            boolean blocked,
            LocalTime closeTime,
            LocalTime latestArrivalBy,
            LocalTime estimatedArrival,
            String message
    ) {
        static CheckResult allowed(LocalTime arrival, LocalTime close, LocalTime latest) {
            return new CheckResult(true, false, close, latest, arrival, null);
        }
    }
}
