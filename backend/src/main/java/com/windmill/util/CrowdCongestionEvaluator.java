package com.windmill.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 혼잡 트리거 판정 — 카테고리 우선, 없으면 장소 평시 대비 %, 마지막에 피크상대 집중률 폴백.
 */
public final class CrowdCongestionEvaluator {

    public enum Level {
        NORMAL,
        WARNING,
        DANGER;

        public boolean isTriggered() {
            return this != NORMAL;
        }

        public boolean isUrgent() {
            return this == DANGER;
        }
    }

    private static final List<String> CATEGORY_FIELDS = List.of(
            "cnctrGrade", "cnctrLevel", "cnctrNm", "congestLvl", "congestionLvl",
            "crowdLevel", "congestGrade", "cnctrCdNm");

    private CrowdCongestionEvaluator() {
    }

    /** TourAPI/KT 응답 한 건에서 카테고리 문자열 추출 (없으면 null) */
    public static String extractCategory(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        for (String field : CATEGORY_FIELDS) {
            String v = textOrNull(item.path(field));
            if (v != null) {
                return v;
            }
        }
        // 숫자 필드가 문자열 카테고리로 오는 드문 경우
        JsonNode rate = item.get("cnctrRate");
        if (rate != null && rate.isTextual()) {
            String t = rate.asText("").trim();
            if (!t.isEmpty() && !isNumeric(t)) {
                return t;
            }
        }
        return null;
    }

    /** cnctrRate 등 수치 파싱. 카테고리 문자열이면 null */
    public static Double extractNumericRate(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        JsonNode rate = item.get("cnctrRate");
        if (rate == null || rate.isNull()) {
            return null;
        }
        if (rate.isNumber()) {
            return rate.asDouble();
        }
        String t = rate.asText("").trim();
        if (t.isEmpty() || !isNumeric(t)) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Level fromCategory(String category) {
        if (category == null || category.isBlank()) {
            return Level.NORMAL;
        }
        String c = category.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "");
        // 긴급
        if (containsAny(c, "매우붐빔", "매우혼잡", "심각", "혼잡심각", "대혼잡", "danger", "verybusy", "verycrowded")) {
            return Level.DANGER;
        }
        // 붐빔 이상 (주의)
        if (containsAny(c, "붐빔", "혼잡", "붐비", "crowded", "busy", "congest")) {
            // "보통혼잡" 같은 애매한 표기는 WARNING으로 보지 않음 — "보통"만 있으면 NORMAL
            if (c.equals("보통") || c.contains("보통") && !c.contains("붐") && !c.contains("혼잡")) {
                return Level.NORMAL;
            }
            if (c.contains("여유") || c.contains("한산") || c.contains("원활")) {
                return Level.NORMAL;
            }
            return Level.WARNING;
        }
        return Level.NORMAL;
    }

    /**
     * 평시 평균 대비 % (오늘/평균×100).
     * 150 → WARNING, 200 → DANGER.
     */
    public static Level fromRelativePercent(Double relativePercent) {
        if (relativePercent == null || !Double.isFinite(relativePercent)) {
            return Level.NORMAL;
        }
        if (relativePercent >= TriggerThresholds.CROWD_RELATIVE_DANGER_PCT) {
            return Level.DANGER;
        }
        if (relativePercent >= TriggerThresholds.CROWD_RELATIVE_WARNING_PCT) {
            return Level.WARNING;
        }
        return Level.NORMAL;
    }

    /** 피크=100 상대 집중률 폴백 */
    public static Level fromPeakRelativeRate(Double cnctrRate) {
        if (cnctrRate == null || !Double.isFinite(cnctrRate)) {
            return Level.NORMAL;
        }
        if (cnctrRate >= TriggerThresholds.CROWD_PEAK_RATE_VERY_BUSY) {
            return Level.DANGER;
        }
        if (cnctrRate >= TriggerThresholds.CROWD_PEAK_RATE_BUSY) {
            return Level.WARNING;
        }
        return Level.NORMAL;
    }

    /**
     * 우선순위: 카테고리 → 평시대비% → 피크상대 집중률.
     */
    public static Level evaluate(String category, Double relativePercent, Double peakRelativeRate) {
        Level fromCat = fromCategory(category);
        if (fromCat.isTriggered()) {
            return fromCat;
        }
        Level fromRel = fromRelativePercent(relativePercent);
        if (fromRel.isTriggered()) {
            return fromRel;
        }
        return fromPeakRelativeRate(peakRelativeRate);
    }

    /** 시리즈 평균 (평시 근사). 값 없으면 null */
    public static Double baselineAverage(Collection<Double> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Double r : rates) {
            if (r != null && Double.isFinite(r)) {
                sum += r;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return sum / n;
    }

    /** 오늘값 / 평시평균 × 100. 평균≤0이면 null */
    public static Double relativePercent(Double today, Double baseline) {
        if (today == null || baseline == null || !Double.isFinite(today) || !Double.isFinite(baseline)) {
            return null;
        }
        if (baseline <= 0) {
            return null;
        }
        return (today / baseline) * 100.0;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String t = node.asText("").trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isNumeric(String t) {
        try {
            Double.parseDouble(t);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
