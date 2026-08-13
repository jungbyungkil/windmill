package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.util.KoreaClock;
import com.windmill.util.SentryBreadcrumbs;

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
    /** detailIntro2 유모차 대여정보 필드(contentType별 접미사) - 자유텍스트("가능"/"불가능"/공란 등) */
    private static final List<String> STROLLER_FIELDS = List.of(
            "chkbabycarriage", "chkbabycarriageculture", "chkbabycarriageleports", "chkbabycarriageshopping");
    /** 무장애/휠체어 관련 구조화 필드가 API에 없어, overview·카테고리 텍스트 키워드 매칭으로 근사한다 */
    private static final List<String> ACCESSIBLE_KEYWORDS = List.of(
            "무장애", "배리어프리", "휠체어", "장애인", "저상", "경사로", "점자");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~-]\\s*(\\d{1,2}):(\\d{2})");
    /** 마감 임박 버퍼(분) — close 1시간 전부터 선택 불가 */
    public static final int CLOSE_BUFFER_MINUTES = 60;
    /** 매월 마지막 (주) X요일 */
    private static final Pattern LAST_WEEKDAY = Pattern.compile(
            "매월\\s*마지막\\s*(?:주\\s*)?([월화수목금토일])요일");
    /** 매주 X요일 (매월 마지막 … 구문은 제외하고 볼 때 사용) */
    private static final Pattern EVERY_WEEKDAY = Pattern.compile(
            "매주\\s*([월화수목금토일])(?:요일)?");
    /**
     * 매월 N번째(·M번째…) X요일 — "매월 두번째·네번째 수요일", "매월 2,4주 수요일", "매월 첫째주 화요일" 등.
     * group(1)에 주차 표기(숫자·서수어 조합)를, group(2)에 요일 한 글자를 담아 WEEK_ORDINAL_TOKEN으로 재파싱한다.
     */
    private static final Pattern NTH_WEEKDAY_CLAUSE = Pattern.compile(
            "매월\\s*([^월화수목금토일]*?)\\s*([월화수목금토일])요일");
    private static final Pattern WEEK_ORDINAL_TOKEN = Pattern.compile(
            "\\d+|첫번째|첫째|첫|두번째|둘째|세번째|셋째|네번째|넷째|다섯번째|다섯째");
    /** "매주"/"매월" 없이 요일명만 적힌 경우("월요일" 단독) - 흔한 축약 표기, 매주 그 요일로 해석 */
    private static final Pattern BARE_WEEKDAY = Pattern.compile("([월화수목금토일])요일");
    private static final String[] WEEKDAY_KO = {"월", "화", "수", "목", "금", "토", "일"};

    private BusinessHoursEvaluator() {
    }

    public static boolean isCurrentlyOpen(JsonNode intro) {
        if (intro == null) {
            return true;
        }
        return isOpenAt(field -> intro.path(field).asText(""), KoreaClock.now());
    }

    public static boolean isCurrentlyOpen(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return true;
        }
        return isOpenAt(field -> introFields.getOrDefault(field, ""), KoreaClock.now());
    }

    /** 방문 예정일·시각 기준 영업 여부 (일정 트리거용) */
    public static boolean isOpenAt(Map<String, String> introFields, LocalDateTime at) {
        if (introFields == null || introFields.isEmpty()) {
            return true;
        }
        LocalDateTime resolved = at == null ? KoreaClock.now() : at;
        SentryBreadcrumbs.timeCalc("business-hours", "isOpenAt 입력=" + at + " 판정기준=" + resolved);
        return isOpenAt(field -> introFields.getOrDefault(field, ""), resolved);
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
        SentryBreadcrumbs.timeCalc("rest-date", "isClosedOnRestDate 판정일=" + date + " restText=" + restText);
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

        // 1.5) 매월 N번째(·M번째…) X요일 — "매월 마지막"과 겹치지 않는 특정 주차 지정만 매칭
        //      (group(1)에 주차 서수/숫자가 하나도 없으면 - 예: "매월 마지막 주" - 아래 루프가 그냥 안 걸림)
        Matcher nth = NTH_WEEKDAY_CLAUSE.matcher(restText);
        while (nth.find()) {
            if (!todayKo.equals(nth.group(2))) {
                continue;
            }
            int currentWeek = weekOfMonth(date);
            Matcher ordinal = WEEK_ORDINAL_TOKEN.matcher(nth.group(1));
            while (ordinal.find()) {
                if (weekOrdinalValue(ordinal.group()) == currentWeek) {
                    return true;
                }
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

        // 3) "매주"/"매월" 접두어 자체가 텍스트에 전혀 없이 요일명만 있는 경우("월요일" 단독) -
        //    실제 TourAPI restdate 필드에 흔한 축약 표기. 위 매월 관련 규칙(1, 1.5)과 겹칠 여지가
        //    없을 때만("매월"/"매주" 미포함) 매주 그 요일 휴무로 해석한다.
        if (!restText.contains("매월") && !restText.contains("매주")) {
            Matcher bare = BARE_WEEKDAY.matcher(restText);
            while (bare.find()) {
                if (todayKo.equals(bare.group(1))) {
                    return true;
                }
            }
        }

        // 4) "공휴일"만 있는 텍스트는 달력 없이 단정하지 않음(임시휴관일 포함 오탐 방지).
        return false;
    }

    /** 해당 날짜가 그 달의 마지막 해당 요일인지 (예: 8월 마지막 일요일) */
    static boolean isLastWeekdayOfMonth(LocalDate date) {
        return date.plusWeeks(1).getMonth() != date.getMonth();
    }

    /** 그 달에서 몇 번째 주인지 (1~5) - "1~7일=첫째 주, 8~14일=둘째 주…" 통상 표기 기준 */
    static int weekOfMonth(LocalDate date) {
        return (date.getDayOfMonth() - 1) / 7 + 1;
    }

    /** "두번째"/"둘째"/"2" 같은 주차 서수 토큰을 1~5 숫자로. 못 알아들으면 0(어느 주와도 매칭 안 됨) */
    private static int weekOrdinalValue(String token) {
        if (Character.isDigit(token.charAt(0))) {
            return Integer.parseInt(token);
        }
        if (token.startsWith("첫")) {
            return 1;
        }
        if (token.equals("둘째") || token.equals("두번째")) {
            return 2;
        }
        if (token.equals("셋째") || token.equals("세번째")) {
            return 3;
        }
        if (token.equals("넷째") || token.equals("네번째")) {
            return 4;
        }
        if (token.equals("다섯째") || token.equals("다섯번째")) {
            return 5;
        }
        return 0;
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

    /** 이용시간 원문 (usetime/opentime 계열 첫 non-blank) */
    public static String extractUseTimeText(Map<String, String> introFields) {
        return firstNonBlank(introFields, USETIME_FIELDS);
    }

    /**
     * 영업 종료(close) 시각. "09:00 ~ 17:00" 형태에서 끝 시각.
     * 야간 영업(종료&lt;시작)도 종료 시각 자체를 반환한다.
     */
    public static LocalTime extractCloseTime(Map<String, String> introFields) {
        String text = extractUseTimeText(introFields);
        return extractCloseTimeFromText(text);
    }

    public static LocalTime extractCloseTimeFromText(String useTimeText) {
        if (useTimeText == null || useTimeText.isBlank()) {
            return null;
        }
        Matcher m = TIME_RANGE.matcher(useTimeText);
        if (!m.find()) {
            return null;
        }
        int h = Integer.parseInt(m.group(3));
        int min = Integer.parseInt(m.group(4));
        if (h > 23 || min > 59) {
            return null;
        }
        return LocalTime.of(h, min);
    }

    public static LocalTime extractOpenTimeFromText(String useTimeText) {
        if (useTimeText == null || useTimeText.isBlank()) {
            return null;
        }
        Matcher m = TIME_RANGE.matcher(useTimeText);
        if (!m.find()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) {
            return null;
        }
        return LocalTime.of(h, min);
    }

    /** "HH:mm" 스냅샷용 */
    public static String formatHhMm(LocalTime time) {
        if (time == null) {
            return null;
        }
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /** 유모차 대여정보 원문 (chkbabycarriage 계열 첫 non-blank) */
    public static String extractStrollerText(Map<String, String> introFields) {
        return firstNonBlank(introFields, STROLLER_FIELDS);
    }

    /**
     * 유모차 이용 가능 여부 추정. "불가능"/"불가" 명시 시 false, "가능" 포함 시 true,
     * 그 외(공란·정보없음 등)는 모르므로 null - isFree와 동일하게 단정하지 않는다.
     */
    public static Boolean isStrollerFriendly(String strollerText) {
        if (strollerText == null || strollerText.isBlank()) {
            return null;
        }
        if (strollerText.contains("불가능") || strollerText.contains("불가")) {
            return false;
        }
        if (strollerText.contains("가능")) {
            return true;
        }
        return null;
    }

    /**
     * 무장애(장애인 편의) 여부 근사 - 구조화 필드가 없어 overview/카테고리 텍스트에서 키워드를 찾는다.
     * 키워드가 없다고 실제로 무장애 시설이 없다는 뜻은 아니므로(휴리스틱), 항상 false만 반환하고 단정은 안 한다.
     */
    public static boolean matchesAccessibleKeyword(String... texts) {
        if (texts == null) {
            return false;
        }
        for (String text : texts) {
            if (text != null && ACCESSIBLE_KEYWORDS.stream().anyMatch(text::contains)) {
                return true;
            }
        }
        return false;
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
