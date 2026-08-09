package com.windmill.util;

import com.windmill.domain.ItineraryItem;

import java.util.List;

/**
 * 일정 항목이 야외(실외) 활동인지 판정.
 * 폭염/비 시 실내 전환 알림·바람개비 경고에 사용한다.
 * #실내·#맛집·도서관·식당 등은 실내로 본다 (#자연과 같이 있어도 실내 우선).
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
            // 실내·먹거리 태그가 있으면 야외 알림 대상이 아님 (#자연과 공존해도 실내 우선)
            if (tags.contains("#실내") || tags.contains("#맛집")) {
                return false;
            }
            if (tags.contains("#자연") || tags.contains("#액티비티")) {
                return true;
            }
        }

        String text = ((item.getCategory() == null ? "" : item.getCategory())
                + " "
                + (item.getPlaceName() == null ? "" : item.getPlaceName())).toLowerCase();

        if (containsAny(text,
                "박물관", "전시", "미술관", "도서관", "아트센터", "예술의전당", "갤러리",
                "카페", "식당", "레스토랑", "맛집", "갈비", "불고기", "물회", "베이커리",
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
