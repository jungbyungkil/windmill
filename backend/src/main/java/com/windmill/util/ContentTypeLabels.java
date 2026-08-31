package com.windmill.util;

/**
 * TourAPI contentTypeId → 카드에 보여줄 한글 카테고리.
 */
public final class ContentTypeLabels {

    private ContentTypeLabels() {
    }

    public static String of(Integer contentTypeId) {
        if (contentTypeId == null) {
            return "장소";
        }
        return switch (contentTypeId) {
            case 12 -> "관광지";
            case 14 -> "문화시설";
            case 15 -> "축제·공연";
            case 25 -> "여행코스";
            case 28 -> "레포츠";
            case 32 -> "숙박";
            case 38 -> "쇼핑";
            case 39 -> "음식점";
            default -> "장소";
        };
    }

    public static Integer parse(String contentTypeId) {
        if (contentTypeId == null || contentTypeId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(contentTypeId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
