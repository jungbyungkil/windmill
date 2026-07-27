package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * detailIntro2 원본 응답(자유 텍스트)으로 현재 영업중 여부를 추정하는 공용 휴리스틱.
 * Stage2BusinessHoursFilter(추천 파이프라인)와 TriggerDetectionService(바람개비 트리거) 양쪽에서 재사용.
 * ⚠ 자유 텍스트 파싱 한계로 시간/휴무 패턴을 못 찾으면 영업중으로 간주하는 보수적 방식.
 */
public final class BusinessHoursEvaluator {

    private static final List<String> USETIME_FIELDS = List.of(
            "usetime", "opentime", "usetimeculture", "usetimefestival", "usetimeleports", "opentimefood");
    private static final List<String> RESTDATE_FIELDS = List.of(
            "restdate", "restdateculture", "restdateshopping", "restdatefood", "restdateleports");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~-]\\s*(\\d{1,2}):(\\d{2})");
    private static final String[] WEEKDAY_KO = {"월", "화", "수", "목", "금", "토", "일"};

    private BusinessHoursEvaluator() {
    }

    public static boolean isCurrentlyOpen(JsonNode intro) {
        if (intro == null) {
            return true;
        }
        return isCurrentlyOpen(field -> intro.path(field).asText(""));
    }

    public static boolean isCurrentlyOpen(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return true;
        }
        return isCurrentlyOpen(field -> introFields.getOrDefault(field, ""));
    }

    private static boolean isCurrentlyOpen(Function<String, String> fieldAccessor) {
        LocalDateTime now = LocalDateTime.now();
        String todayKo = WEEKDAY_KO[now.getDayOfWeek().getValue() - 1];

        for (String field : RESTDATE_FIELDS) {
            String v = fieldAccessor.apply(field);
            if (!v.isBlank() && !v.contains("연중무휴") && !v.contains("없음")
                    && (v.contains(todayKo + "요일") || v.contains("매주 " + todayKo))) {
                return false;
            }
        }

        for (String field : USETIME_FIELDS) {
            String v = fieldAccessor.apply(field);
            Matcher m = TIME_RANGE.matcher(v);
            if (m.find()) {
                LocalTime start = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                LocalTime end = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                LocalTime nowTime = now.toLocalTime();
                if (end.isBefore(start)) {
                    return nowTime.isAfter(start) || nowTime.isBefore(end);
                }
                return !nowTime.isBefore(start) && !nowTime.isAfter(end);
            }
        }
        return true;
    }
}
