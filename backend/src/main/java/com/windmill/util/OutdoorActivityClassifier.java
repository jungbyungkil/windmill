package com.windmill.util;

import com.windmill.domain.ItineraryItem;

import java.util.List;

/**
 * 일정 항목이 야외(실외) 활동인지 판정.
 * 폭염 시 실내 전환 알림·바람개비 경고에 사용한다.
 */
public final class OutdoorActivityClassifier {

    private OutdoorActivityClassifier() {
    }

    public static boolean isOutdoor(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        List<String> tags = item.getTags();
        if (tags != null) {
            if (tags.contains("#실내")) {
                return false;
            }
            if (tags.contains("#자연") || tags.contains("#액티비티")) {
                return true;
            }
        }

        String text = ((item.getCategory() == null ? "" : item.getCategory())
                + " "
                + (item.getPlaceName() == null ? "" : item.getPlaceName())).toLowerCase();

        if (containsAny(text, "박물관", "전시", "미술관", "카페", "식당", "레스토랑", "실내", "키즈카페", "갤러리")) {
            return false;
        }
        if (containsAny(text, "해변", "해수욕", "산 ", "등산", "공원", "워터파크", "야외", "산책", "캠핑", "수상", "스키")) {
            return true;
        }

        Integer type = item.getContentTypeId();
        if (type == null) {
            return false;
        }
        // 12 관광지 · 28 레포츠 → 야외 경향, 14 문화시설 · 39 음식점 → 실내 경향
        return type == 12 || type == 28;
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
