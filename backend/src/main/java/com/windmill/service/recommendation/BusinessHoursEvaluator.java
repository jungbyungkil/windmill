package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.dto.BusinessStatus;
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
    /** "성인 5,000원", "어른 5,000원 / 청소년 3,000원" 같은 표기에서 라벨+금액을 뽑는다 */
    private static final Pattern COST_AMOUNT = Pattern.compile("([가-힣]*)\\s*(\\d[,\\d]*)\\s*원");
    private static final List<String> ADULT_FEE_LABELS = List.of("성인", "어른", "대인");
    private static final List<String> PHONE_FIELDS = List.of(
            "infocenter", "infocenterculture", "infocenterleports", "infocenterfood", "infocenterfestival", "infocenterlodging");
    /** detailIntro2 유모차 대여정보 필드(contentType별 접미사) - 자유텍스트("가능"/"불가능"/공란 등) */
    private static final List<String> STROLLER_FIELDS = List.of(
            "chkbabycarriage", "chkbabycarriageculture", "chkbabycarriageleports", "chkbabycarriageshopping");
    /** 무장애/휠체어 관련 구조화 필드가 API에 없어, overview·카테고리 텍스트 키워드 매칭으로 근사한다 */
    private static final List<String> ACCESSIBLE_KEYWORDS = List.of(
            "무장애", "배리어프리", "휠체어", "장애인", "저상", "경사로", "점자");
    /** detailIntro2 체험가능연령 필드(contentType별 접미사) - 자유텍스트("만 7세 이상" 등), 라이브 확인(2026-08-14, contentTypeId=12) */
    private static final List<String> AGE_RANGE_FIELDS = List.of(
            "expagerange", "expagerangeculture", "expagerangeleports");
    /** "만 7세 이상", "8세이상", "7세 부터" 같은 하한 나이 표기 - "이상"/"부터"가 붙은 숫자만 하한으로 인정
     *  ("10세 미만 무료"처럼 상한을 나타내는 문장을 하한으로 오독하지 않기 위함) */
    private static final Pattern AGE_LOWER_BOUND = Pattern.compile("(\\d{1,2})\\s*세\\s*(?:이상|부터)");
    /** "13세~50세", "13세-50세" 같은 범위 표기 - 라이브 확인(2026-08-14, 번지점프 expagerangeleports="13세~50세") */
    private static final Pattern AGE_RANGE_TILDE = Pattern.compile("(\\d{1,2})\\s*세\\s*[~-]\\s*\\d{1,2}\\s*세");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~-]\\s*(\\d{1,2}):(\\d{2})");
    /**
     * "오전/오후 H시(MM분)?~오전/오후 H시(MM분)?" 12시간제 표기 - TIME_RANGE(콜론 24시간제)가 못 찾을 때만
     * 폴백으로 시도한다("HH시~HH시"처럼 오전/오후 없이 "시"만 쓴 24시간 표기도 함께 커버).
     */
    private static final Pattern AMPM_TIME_RANGE = Pattern.compile(
            "(오전|오후)?\\s*(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분)?\\s*[~-]\\s*(오전|오후)?\\s*(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분)?");
    /** 마감 임박 버퍼(분) — close 1시간 전부터 선택 불가 */
    public static final int CLOSE_BUFFER_MINUTES = 60;
    /** 매월(매달) 마지막 (주) X요일 */
    private static final Pattern LAST_WEEKDAY = Pattern.compile(
            "(?:매월|매달)\\s*마지막\\s*(?:주\\s*)?([월화수목금토일])요일");
    /** 매주 X요일 (매월 마지막 … 구문은 제외하고 볼 때 사용) */
    private static final Pattern EVERY_WEEKDAY = Pattern.compile(
            "매주\\s*([월화수목금토일])(?:요일)?");
    /** 매주 X요일~Y요일 (요일 구간) - 예: "매주 월요일~수요일". 라이브 표본(2026-08-15, 가평 소재 음식점)에서 실제 확인 */
    private static final Pattern EVERY_WEEKDAY_RANGE = Pattern.compile(
            "매주\\s*([월화수목금토일])요일\\s*[~-]\\s*([월화수목금토일])요일");
    /**
     * 매월(매달) N번째(·M번째…) X요일 — "매월 두번째·네번째 수요일", "매달 셋째·넷째·다섯째 목요일",
     * "매월 2,4주 수요일", "매월 첫째주 화요일" 등. group(1)에 주차 표기(숫자·서수어 조합)를, group(2)에
     * 요일 한 글자를 담아 WEEK_ORDINAL_TOKEN으로 재파싱한다.
     */
    private static final Pattern NTH_WEEKDAY_CLAUSE = Pattern.compile(
            "(?:매월|매달)\\s*([^월화수목금토일]*?)\\s*([월화수목금토일])요일");
    private static final Pattern WEEK_ORDINAL_TOKEN = Pattern.compile(
            "\\d+|첫번째|첫째|첫|두번째|둘째|세번째|셋째|네번째|넷째|다섯번째|다섯째");
    /** "매주"/"매월"/"매달" 없이 요일명만 적힌 경우("월요일" 단독) - 흔한 축약 표기, 매주 그 요일로 해석 */
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

    /** 지금 이 순간 영업 상태(휴무/영업종료/영업중 구분) - 추천 카드 배지용 */
    public static BusinessStatus currentStatus(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return BusinessStatus.OPEN;
        }
        return statusAt(field -> introFields.getOrDefault(field, ""), KoreaClock.now());
    }

    /** 방문 예정일·시각 기준 영업 여부 (일정 트리거용) */
    public static boolean isOpenAt(Map<String, String> introFields, LocalDateTime at) {
        return statusAt(introFields, at) == BusinessStatus.OPEN;
    }

    /** 방문 예정일·시각 기준 영업 상태(휴무/영업종료/영업중 구분) - 일정 트리거 배지 구분용 */
    public static BusinessStatus statusAt(Map<String, String> introFields, LocalDateTime at) {
        if (introFields == null || introFields.isEmpty()) {
            return BusinessStatus.OPEN;
        }
        LocalDateTime resolved = at == null ? KoreaClock.now() : at;
        SentryBreadcrumbs.timeCalc("business-hours", "isOpenAt 입력=" + at + " 판정기준=" + resolved);
        return statusAt(field -> introFields.getOrDefault(field, ""), resolved);
    }

    /**
     * "매주 월요일(단, 월요일이 공휴일인 경우 다음날 휴관)"류 - 공휴일과 겹치면 휴무가 밀리는 안내
     * 문구 감지. 실제 원문마다 "공휴일"과 트리거 단어의 순서·거리가 제각각이라(예: 덕수궁 원문은
     * "그 다음의 첫 번째 비공휴일이 정기휴일임"처럼 "다음"이 "공휴일"보다 앞에 옴, "겹치다"도 어미가
     * "겹칠"/"겹쳐"처럼 활용됨) 위치 기반 정규식 대신 단순 공존 여부로 판단한다.
     */
    private static boolean hasHolidayShiftClause(String restText) {
        return restText.contains("공휴일")
                && (restText.contains("다음") || restText.contains("겹치") || restText.contains("겹칠")
                        || restText.contains("밀림") || restText.contains("이동"));
    }

    /**
     * 정기휴무 원문만으로 해당 날짜가 휴무인지 판정.
     * 예: "매월 마지막 주 일요일" → 그달 마지막 일요일만 true.
     *     "매주 일요일" → 모든 일요일 true.
     * 정기휴무 요일이 공휴일과 겹치면 다음(비공휴일) 날로 밀린다는 안내가 원문에 있으면, 실제 공휴일
     * 달력(KoreanHolidayCache)을 대조해 그 예외를 반영한다(2026-08-16 사용자 제보 - 덕수궁·서울도시
     * 건축전시관 둘 다 "월요일, 단 공휴일이면 다음날 휴관" 조건이 있는데 8/17(월, 대체공휴일)을 요일만
     * 보고 휴무로 오판정했음. 8/17은 열려있어야 하고 대신 8/18이 휴무여야 함).
     */
    public static boolean isClosedOnRestDate(String restText, LocalDate date) {
        if (restText == null || restText.isBlank() || date == null) {
            return false;
        }
        SentryBreadcrumbs.timeCalc("rest-date", "isClosedOnRestDate 판정일=" + date + " restText=" + restText);
        if (restText.contains("연중무휴") || restText.contains("휴무 없음") || restText.equals("없음")) {
            return false;
        }

        boolean weekdayClosed = isClosedByWeekdayRule(restText, date);
        if (!hasHolidayShiftClause(restText)) {
            return weekdayClosed;
        }
        if (weekdayClosed) {
            // 정기휴무 요일인데 공휴일과 겹치면 그날은 예외로 개방
            return !KoreanHolidayCache.isHoliday(date);
        }
        // 정기휴무 요일이 아니어도, 직전 정기휴무 요일이 공휴일이었고 그 뒤로 date 전날까지 전부
        // 공휴일이 이어졌다면(연휴) date가 "그 다음 첫 비공휴일"로 밀린 휴무일이다.
        LocalDate cursor = date.minusDays(1);
        for (int i = 0; i < 7; i++) {
            if (!KoreanHolidayCache.isHoliday(cursor)) {
                return false;
            }
            if (isClosedByWeekdayRule(restText, cursor)) {
                return true;
            }
            cursor = cursor.minusDays(1);
        }
        return false;
    }

    /** 공휴일 예외를 뺀 순수 요일/주차 규칙 판정 - isClosedOnRestDate 내부 전용 */
    private static boolean isClosedByWeekdayRule(String restText, LocalDate date) {
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

        // 1.8) 매주 X요일~Y요일 (요일 구간) - 라이브 표본에서 실제 확인된 표기("매주 월요일~수요일")
        Matcher weekdayRange = EVERY_WEEKDAY_RANGE.matcher(restText);
        while (weekdayRange.find()) {
            int from = weekdayIndex(weekdayRange.group(1));
            int to = weekdayIndex(weekdayRange.group(2));
            if (isWeekdayInRange(todayKo, from, to)) {
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

        // 2.5) "주말" - 토요일·일요일 정기휴무의 흔한 축약 표기. 라이브 확인(2026-08-15, 가평문화원
        //      restdateculture="주말 /공휴일") - 이 표기를 못 읽어 토요일에도 "영업중"으로 오판정하던 버그.
        if (restText.contains("주말") && (todayKo.equals("토") || todayKo.equals("일"))) {
            return true;
        }

        // 3) "매주"/"매월"/"매달" 접두어 자체가 텍스트에 전혀 없이 요일명만 있는 경우("월요일" 단독) -
        //    실제 TourAPI restdate 필드에 흔한 축약 표기. 위 매월(매달) 관련 규칙(1, 1.5)과 겹칠 여지가
        //    없을 때만("매월"/"매달"/"매주" 미포함) 매주 그 요일 휴무로 해석한다.
        if (!restText.contains("매월") && !restText.contains("매달") && !restText.contains("매주")) {
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

    /** WEEKDAY_KO 배열 인덱스(0=월~6=일). 못 찾으면 -1 */
    private static int weekdayIndex(String weekdayKo) {
        for (int i = 0; i < WEEKDAY_KO.length; i++) {
            if (WEEKDAY_KO[i].equals(weekdayKo)) {
                return i;
            }
        }
        return -1;
    }

    /** today가 from~to 요일 구간(월요일 기준 순환)에 포함되는지 - "금요일~월요일"처럼 주 경계를 넘는 구간도 지원 */
    private static boolean isWeekdayInRange(String todayKo, int from, int to) {
        int today = weekdayIndex(todayKo);
        if (today < 0 || from < 0 || to < 0) {
            return false;
        }
        if (from <= to) {
            return today >= from && today <= to;
        }
        return today >= from || today <= to;
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
        return statusAt(fieldAccessor, at) == BusinessStatus.OPEN;
    }

    /** 휴무(정기휴무 요일)와 영업종료(영업시간 밖)를 구분해서 판정 */
    private static BusinessStatus statusAt(Function<String, String> fieldAccessor, LocalDateTime at) {
        LocalDate date = at.toLocalDate();

        for (String field : RESTDATE_FIELDS) {
            String v = fieldAccessor.apply(field);
            if (!v.isBlank() && isClosedOnRestDate(v, date)) {
                return BusinessStatus.CLOSED_DAY;
            }
        }

        for (String field : USETIME_FIELDS) {
            String v = fieldAccessor.apply(field);
            int[] range = parseTimeRange(v);
            if (range != null) {
                LocalTime start = LocalTime.of(range[0], range[1]);
                LocalTime end = LocalTime.of(range[2], range[3]);
                LocalTime nowTime = at.toLocalTime();
                boolean open = end.isBefore(start)
                        ? (nowTime.isAfter(start) || nowTime.isBefore(end))
                        : (!nowTime.isBefore(start) && !nowTime.isAfter(end));
                return open ? BusinessStatus.OPEN : BusinessStatus.HOURS_ENDED;
            }
        }
        return BusinessStatus.OPEN;
    }

    /**
     * "09:00~18:00"(24시간제, 우선) 또는 "오전 9시~오후 6시"/"10시~18시"(12시간제·오전오후 폴백)에서
     * [시작시,시작분,종료시,종료분]을 뽑는다. 둘 다 못 찾거나 시/분이 범위를 벗어나면 null.
     */
    private static int[] parseTimeRange(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = TIME_RANGE.matcher(text);
        if (m.find()) {
            return validRangeOrNull(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        }
        Matcher am = AMPM_TIME_RANGE.matcher(text);
        if (am.find()) {
            int startH = resolveMeridiemHour(am.group(1), Integer.parseInt(am.group(2)));
            int startM = am.group(3) != null ? Integer.parseInt(am.group(3)) : 0;
            int endH = resolveMeridiemHour(am.group(4), Integer.parseInt(am.group(5)));
            int endM = am.group(6) != null ? Integer.parseInt(am.group(6)) : 0;
            return validRangeOrNull(startH, startM, endH, endM);
        }
        return null;
    }

    private static int[] validRangeOrNull(int startH, int startM, int endH, int endM) {
        if (startH > 23 || startM > 59 || endH > 23 || endM > 59) {
            return null;
        }
        return new int[]{startH, startM, endH, endM};
    }

    /** "오후 1~11시"는 +12, "오후 12시"는 정오 그대로, "오전 12시"는 자정(0시), 그 외 오전/미지정은 그대로 */
    private static int resolveMeridiemHour(String meridiem, int hour) {
        if ("오후".equals(meridiem)) {
            return hour == 12 ? 12 : hour + 12;
        }
        if ("오전".equals(meridiem) && hour == 12) {
            return 0;
        }
        return hour;
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

    /**
     * useFeeText에서 1인 기준 예상 비용(원)을 추정한다. isFree=true(무료)면 0, "성인"/"어른"/"대인"
     * 라벨이 붙은 금액이 있으면 그 금액을, 없으면 텍스트에서 처음 찾은 금액을 쓴다. 금액 패턴을 하나도
     * 못 찾으면(예: "별도 문의", 원문 자체가 없음) null - "무료"(0원)와 구분해야 하므로 0으로 단정하지 않는다.
     */
    public static Integer extractCostAmount(String useFeeText) {
        if (Boolean.TRUE.equals(isFree(useFeeText))) {
            return 0;
        }
        if (useFeeText == null || useFeeText.isBlank()) {
            return null;
        }
        Matcher m = COST_AMOUNT.matcher(useFeeText);
        Integer firstAmount = null;
        while (m.find()) {
            int amount = parseAmount(m.group(2));
            if (firstAmount == null) {
                firstAmount = amount;
            }
            String label = m.group(1);
            if (label != null && ADULT_FEE_LABELS.stream().anyMatch(label::contains)) {
                return amount;
            }
        }
        return firstAmount;
    }

    private static int parseAmount(String digits) {
        return Integer.parseInt(digits.replace(",", ""));
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
        int[] range = parseTimeRange(useTimeText);
        if (range == null) {
            return null;
        }
        return LocalTime.of(range[2], range[3]);
    }

    public static LocalTime extractOpenTimeFromText(String useTimeText) {
        int[] range = parseTimeRange(useTimeText);
        if (range == null) {
            return null;
        }
        int h = range[0];
        int min = range[1];
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

    /** 체험가능연령 원문 (expagerange 계열 첫 non-blank) */
    public static String extractAgeRangeText(Map<String, String> introFields) {
        return firstNonBlank(introFields, AGE_RANGE_FIELDS);
    }

    /**
     * "만 7세 이상"류 문구에서 하한 나이만 뽑는다. 못 찾으면 null(제한 없음/모름 - AgeGroupRanking은
     * null이면 그 후보를 순위 조정 없이 그대로 둔다, isFree와 동일하게 단정하지 않는 원칙).
     */
    public static Integer parseMinAge(String ageRangeText) {
        if (ageRangeText == null || ageRangeText.isBlank()) {
            return null;
        }
        Matcher lower = AGE_LOWER_BOUND.matcher(ageRangeText);
        if (lower.find()) {
            return parseIntOrNull(lower.group(1));
        }
        Matcher range = AGE_RANGE_TILDE.matcher(ageRangeText);
        if (range.find()) {
            return parseIntOrNull(range.group(1));
        }
        return null;
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
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
