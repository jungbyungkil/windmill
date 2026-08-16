package com.windmill.util;

import com.windmill.domain.ItineraryItem;

import java.util.List;

/**
 * 일정 항목이 야외(실외) 활동인지 판정.
 * 폭염/비 시 실내 전환 알림·바람개비 경고에 사용한다.
 * #실내·#맛집·도서관·식당·카페 등은 실내로 본다.
 */
public final class OutdoorActivityClassifier {

    private OutdoorActivityClassifier() {
    }

    public static boolean isOutdoor(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        // 명시적 태그가 최우선 - #실내/#맛집이 있으면 contentTypeId(예: 39=음식점)의 실내 추정보다 먼저 실내로 확정한다.
        if (hasExplicitIndoorTag(item)) {
            return false;
        }
        if (hasExplicitOutdoorTag(item)) {
            return true;
        }

        String text = ((item.getCategory() == null ? "" : item.getCategory())
                + " "
                + (item.getPlaceName() == null ? "" : item.getPlaceName())).toLowerCase();

        if (containsAny(text,
                "박물관", "전시", "미술관", "도서관", "아트센터", "예술의전당", "갤러리",
                "카페", "식당", "레스토랑", "맛집", "갈비", "불고기", "물회", "베이커리", "제과",
                "실내", "키즈카페", "영화관", "쇼핑몰", "백화점", "온천", "찜질")) {
            return false;
        }
        if (containsAny(text, "해변", "해수욕", "산 ", "등산", "공원", "워터파크", "야외", "산책", "캠핑", "수상", "스키")) {
            return true;
        }

        Integer type = item.getContentTypeId();
        if (type == null) {
            return false;
        }
        if (type == 14 || type == 39) {
            return false;
        }
        // 12 관광지 · 28 레포츠 → 야외 경향 (다른 신호가 없을 때만)
        return type == 12 || type == 28;
    }

    /** #실내·#맛집·카페/식당명·문화시설/음식점 타입이면 실내 */
    public static boolean hasIndoorSignal(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        if (hasExplicitIndoorTag(item)) {
            return true;
        }
        String text = ((item.getCategory() == null ? "" : item.getCategory())
                + " "
                + (item.getPlaceName() == null ? "" : item.getPlaceName())).toLowerCase();
        if (containsAny(text,
                "박물관", "전시", "미술관", "도서관", "아트센터", "예술의전당", "갤러리",
                "카페", "식당", "레스토랑", "맛집", "갈비", "불고기", "물회", "베이커리", "제과",
                "실내", "키즈카페", "영화관", "쇼핑몰", "백화점", "온천", "찜질")) {
            return true;
        }
        Integer type = item.getContentTypeId();
        return type != null && (type == 14 || type == 39);
    }

    private static boolean hasExplicitIndoorTag(ItineraryItem item) {
        List<String> tags = item.getTags();
        if (tags == null) {
            return false;
        }
        for (String raw : tags) {
            String t = normalizeTag(raw);
            if ("#실내".equals(t) || "#맛집".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExplicitOutdoorTag(ItineraryItem item) {
        List<String> tags = item.getTags();
        if (tags == null) {
            return false;
        }
        for (String raw : tags) {
            String t = normalizeTag(raw);
            if ("#자연".equals(t) || "#액티비티".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeTag(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return "";
        }
        if (!t.startsWith("#")) {
            t = "#" + t;
        }
        return t;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
