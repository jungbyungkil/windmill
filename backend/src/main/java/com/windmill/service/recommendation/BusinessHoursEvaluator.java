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
 * 신규 기능(정기휴무 표시/입장료 무료여부/전화번호) 요구사항에 맞춰 같은 introFields에서
 * restdate/usefee/infocenter 원문 텍스트를 뽑아주는 헬퍼도 함께 둔다 - 영업시간 판정과 같은
 * "contentTypeId별로 필드명이 다르다"는 문제를 겪으므로 한 곳에서 관리한다.
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

    /**
     * 정기휴무 원문 텍스트(예: "매주 월요일") - contentType별 restdate* 필드 중 첫 non-blank 값.
     * "연중무휴"/"없음"은 "휴무 없음"이라는 뜻이라 정기휴무 텍스트로 취급하지 않는다(isCurrentlyOpen과 동일 기준).
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

    /** 이용요금 원문 텍스트(예: "무료", "성인 3,000원") - contentType별 usefee* 필드 중 첫 non-blank 값. 없으면 null */
    public static String extractUseFeeText(Map<String, String> introFields) {
        return firstNonBlank(introFields, USEFEE_FIELDS);
    }

    /**
     * usefee 원문 텍스트로 무료 여부 추정. "무료"/"없음" 포함 시 true, 숫자+"원"/"₩" 패턴이 있으면 false,
     * 텍스트가 없거나 애매하면 null(모름) - false로 단정해 무료 장소를 놓치는 것을 피한다.
     */
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

    /** 전화번호 - detailCommon2의 tel을 우선하고, 없으면 detailIntro2의 infocenter* 필드로 폴백 */
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
