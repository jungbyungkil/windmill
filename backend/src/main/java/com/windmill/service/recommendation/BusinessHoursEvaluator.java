package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * detailIntro2 원본 응답(자유 텍스트)으로 영업중·휴무 여부를 추정하는 공용 휴리스틱.
 * Stage2BusinessHoursFilter · TriggerDetectionService 양쪽에서 재사용.
 * ⚠ 패턴을 못 찾으면 영업중으로 간주하는 보수적 방식.
 */
public final class BusinessHoursEvaluator {

    private static final List<String> USETIME_FIELDS = List.of(
            "usetime", "opentime", "usetimeculture", "usetimefestival", "usetimeleports", "opentimefood");
    private static final List<String> RESTDATE_FIELDS = List.of(
            "restdate", "restdateculture", "restdateshopping", "restdatefood", "restdateleports");
    private static final List<String> USEFEE_FIELDS = List.of(
            "usefee", "usefeeculture", "usefeeleports", "usefeefestival");
    private static final List<String> PHONE_FIELDS = List.of(
            "infocenter", "infocenterculture", "infocenterleports", "infocenterfood", "infocenterfestival", "infocenterlodging");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~-]\\s*(\\d{1,2}):(\\d{2})");
    /** 매월 마지막 (주) X요일 */
    private static final Pattern LAST_WEEKDAY = Pattern.compile(
            "매월\\s*마지막\\s*(?:주\\s*)?([월화수목금토일])요일");
    /** 매주 X요일 (매월 마지막 … 구문은 제외하고 볼 때 사용) */
    private static final Pattern EVERY_WEEKDAY = Pattern.compile(
            "매주\\s*([월화수목금토일])(?:요일)?");
    private static final String[] WEEKDAY_KO = {"월", "화", "수", "목", "금", "토", "일"};

    private BusinessHoursEvaluator() {
    }

    public static boolean isCurrentlyOpen(JsonNode intro) {
        if (intro == null) {
            return true;
        }
        return isOpenAt(field -> intro.path(field).asText(""), LocalDateTime.now());
    }

    public static boolean isCurrentlyOpen(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return true;
        }
        return isOpenAt(field -> introFields.getOrDefault(field, ""), LocalDateTime.now());
    }

    /** 방문 예정일·시각 기준 영업 여부 (일정 트리거용) */
    public static boolean isOpenAt(Map<String, String> introFields, LocalDateTime at) {
        if (introFields == null || introFields.isEmpty()) {
            return true;
        }
        return isOpenAt(field -> introFields.getOrDefault(field, ""), at == null ? LocalDateTime.now() : at);
    }

    /**
     * 정기휴무 원문만으로 해당 날짜가 휴무인지 판정.
     * 예: "매월 마지막 주 일요일" → 그달 마지막 일요일만 true.
     *     "매주 일요일" → 모든 일요일 true.
     */
    public static boolean isClosedOnRestDate(String restText, LocalDate date) {
        if (restText == null || restText.isBlank() || date == null) {
            return false;
        }
        if (restText.contains("연중무휴") || restText.contains("휴무 없음") || restText.equals("없음")) {
            return false;
        }

        String todayKo = WEEKDAY_KO[date.getDayOfWeek().getValue() - 1];
        boolean lastWeekdayOfMonth = isLastWeekdayOfMonth(date);

        // 1) 매월 마지막 (주) X요일 — "일요일" 단독 매칭보다 먼저·정확히
        Matcher last = LAST_WEEKDAY.matcher(restText);
        boolean matchedLastRule = false;
        while (last.find()) {
            matchedLastRule = true;
            if (todayKo.equals(last.group(1)) && lastWeekdayOfMonth) {
                return true;
            }
        }

        // 2) 매주 X요일 — 같은 요일의 "마지막 주" 규칙만 있는 경우는 제외
        Matcher every = EVERY_WEEKDAY.matcher(restText);
        while (every.find()) {
            String day = every.group(1);
            if (!todayKo.equals(day)) {
                continue;
            }
            // "매월 마지막 주 일요일" 안에 "일요일"이 있어도 EVERY에는 안 걸림(매주 없음).
            // 혹시 "매주·매월 마지막 일" 혼용이면 마지막 주가 아닐 때는 열림.
            if (matchedLastRule && restText.contains("마지막") && restText.contains(day + "요일")) {
                continue;
            }
            return true;
        }

        // 3) 레거시: "매주 일" 형태 없이 "매주 일요일"만 있는 경우 이미 EVERY로 처리됨.
        //    "공휴일"만 있는 텍스트는 달력 없이 단정하지 않음(임시휴관일 포함 오탐 방지).
        return false;
    }

    /** 해당 날짜가 그 달의 마지막 해당 요일인지 (예: 8월 마지막 일요일) */
    static boolean isLastWeekdayOfMonth(LocalDate date) {
        return date.plusWeeks(1).getMonth() != date.getMonth();
    }

    private static boolean isOpenAt(Function<String, String> fieldAccessor, LocalDateTime at) {
        LocalDate date = at.toLocalDate();

        for (String field : RESTDATE_FIELDS) {
            String v = fieldAccessor.apply(field);
            if (!v.isBlank() && isClosedOnRestDate(v, date)) {
                return false;
            }
        }

        for (String field : USETIME_FIELDS) {
            String v = fieldAccessor.apply(field);
            Matcher m = TIME_RANGE.matcher(v);
            if (m.find()) {
                LocalTime start = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                LocalTime end = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                LocalTime nowTime = at.toLocalTime();
                if (end.isBefore(start)) {
                    return nowTime.isAfter(start) || nowTime.isBefore(end);
                }
                return !nowTime.isBefore(start) && !nowTime.isAfter(end);
            }
        }
        return true;
    }

    /**
     * 정기휴무 원문 텍스트(예: "매주 월요일") - contentType별 restdate* 필드 중 첫 non-blank 값.
     */
    public static String extractRestDateText(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return null;
        }
        for (String field : RESTDATE_FIELDS) {
            String v = introFields.get(field);
            if (v != null && !v.isBlank() && !v.contains("연중무휴") && !v.contains("없음")) {
                return v;
            }
        }
        return null;
    }

    public static String extractUseFeeText(Map<String, String> introFields) {
        return firstNonBlank(introFields, USEFEE_FIELDS);
    }

    public static Boolean isFree(String useFeeText) {
        if (useFeeText == null || useFeeText.isBlank()) {
            return null;
        }
        if (useFeeText.contains("무료") || useFeeText.contains("없음")) {
            return true;
        }
        if (Pattern.compile("\\d[,\\d]*\\s*원").matcher(useFeeText).find() || useFeeText.contains("₩")) {
            return false;
        }
        return null;
    }

    public static String extractPhone(String tel, Map<String, String> introFields) {
        if (tel != null && !tel.isBlank()) {
            return tel;
        }
        return firstNonBlank(introFields, PHONE_FIELDS);
    }

    private static String firstNonBlank(Map<String, String> introFields, List<String> fields) {
        if (introFields == null || introFields.isEmpty()) {
            return null;
        }
        for (String field : fields) {
            String v = introFields.get(field);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
