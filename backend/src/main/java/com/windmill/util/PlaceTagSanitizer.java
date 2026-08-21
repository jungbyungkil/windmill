package com.windmill.util;

import com.windmill.domain.RecommendThemeTag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 장소 태그 정리 — 체험관·관광지에 LLM/#맛집 오탐이 붙는 것을 막는다.
 * TourAPI contentTypeId: 12 관광지, 14 문화시설, 28 레포츠, 39 음식점.
 */
public final class PlaceTagSanitizer {

    private PlaceTagSanitizer() {
    }

    /**
     * @param requestedTags Stage4 요청 태그(있으면 이 집합 안에서만 유지). null이면 테마 enum 전체 허용 후 타입으로 걸러냄.
     */
    public static List<String> sanitize(List<String> rawTags,
                                        Integer contentTypeId,
                                        String placeName,
                                        String category,
                                        List<String> requestedTags) {
        Set<String> allowedRequest = normalizeAllowed(requestedTags);
        Set<String> out = new LinkedHashSet<>();

        if (rawTags != null) {
            for (String raw : rawTags) {
                String tag = normalizeTag(raw);
                if (tag == null) {
                    continue;
                }
                if (!allowedRequest.isEmpty() && !allowedRequest.contains(tag)) {
                    continue;
                }
                if (RecommendThemeTag.fromTag(tag) == null) {
                    continue;
                }
                out.add(tag);
            }
        }

        boolean foodPlace = looksLikeFood(contentTypeId, placeName, category);
        if (!foodPlace) {
            out.remove("#맛집");
        } else {
            out.add("#맛집");
        }

        // 요청 태그가 있었는데 전부 비었으면, 장소 성격에 맞는 요청 태그만 채움
        if (out.isEmpty() && !allowedRequest.isEmpty()) {
            for (String t : allowedRequest) {
                if ("#맛집".equals(t) && !foodPlace) {
                    continue;
                }
                if (fitsTheme(t, contentTypeId, placeName, category)) {
                    out.add(t);
                }
            }
        }

        if (out.isEmpty()) {
            out.addAll(defaultTags(contentTypeId, placeName, category));
        }
        return new ArrayList<>(out);
    }

    public static List<String> sanitizeStored(List<String> rawTags,
                                              Integer contentTypeId,
                                              String placeName,
                                              String category) {
        return sanitize(rawTags, contentTypeId, placeName, category, null);
    }

    public static boolean looksLikeCafe(Integer contentTypeId, String placeName, String category, List<String> tags) {
        String text = ((placeName == null ? "" : placeName) + " "
                + (category == null ? "" : category) + " "
                + (tags == null ? "" : String.join(" ", tags))).toLowerCase(Locale.ROOT);
        if (containsAny(text, "카페", "커피", "cafe", "coffee", "디저트", "베이커리", "브런치")) {
            return true;
        }
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && tag.contains("카페")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean looksLikeFood(Integer contentTypeId, String placeName, String category) {
        if (contentTypeId != null && contentTypeId == 39) {
            return true;
        }
        // 문화시설·관광지·레포츠는 이름에 '맛집'이 있어도 음식점으로 보지 않음
        if (contentTypeId != null && (contentTypeId == 12 || contentTypeId == 14 || contentTypeId == 28)) {
            return false;
        }
        String text = ((category == null ? "" : category) + " " + (placeName == null ? "" : placeName))
                .toLowerCase(Locale.ROOT);
        if (containsAny(text, "전시", "체험관", "박물관", "미술관", "스테이션", "기념관", "도서관",
                "미술관", "갤러리", "문화센터", "문화회관", "과학관", "타워", "공원")) {
            return false;
        }
        return containsAny(text, "식당", "맛집", "레스토랑", "음식", "카페", "베이커리", "제과",
                "갈비", "국밥", "한식", "중식", "일식", "치킨", "피자", "술집", "주점");
    }

    private static List<String> defaultTags(Integer contentTypeId, String placeName, String category) {
        List<String> tags = new ArrayList<>();
        if (looksLikeFood(contentTypeId, placeName, category)) {
            tags.add("#맛집");
            return tags;
        }
        String text = ((category == null ? "" : category) + " " + (placeName == null ? "" : placeName))
                .toLowerCase(Locale.ROOT);
        if (contentTypeId != null && contentTypeId == 14
                || containsAny(text, "박물관", "전시", "미술관", "체험관", "갤러리", "실내", "스테이션")) {
            tags.add("#실내");
        }
        if (containsAny(text, "체험", "키즈", "어린이", "가족", "아이")
                || (contentTypeId != null && contentTypeId == 28)) {
            tags.add("#아이동반");
        }
        if (containsAny(text, "역사", "유적", "기념관", "문화재")) {
            tags.add("#역사");
        }
        if (contentTypeId != null && contentTypeId == 12
                || containsAny(text, "공원", "자연", "호수", "산책")) {
            tags.add("#자연");
        }
        if (tags.isEmpty()) {
            tags.add("#실내");
        }
        return tags;
    }

    private static boolean fitsTheme(String tag, Integer contentTypeId, String placeName, String category) {
        if ("#맛집".equals(tag)) {
            return looksLikeFood(contentTypeId, placeName, category);
        }
        if ("#실내".equals(tag)) {
            return contentTypeId == null || contentTypeId == 14 || contentTypeId == 39
                    || containsAny(((placeName == null ? "" : placeName) + " " + (category == null ? "" : category))
                    .toLowerCase(Locale.ROOT), "실내", "박물관", "전시", "체험", "스테이션", "카페");
        }
        return true;
    }

    private static Set<String> normalizeAllowed(List<String> requestedTags) {
        Set<String> allowed = new LinkedHashSet<>();
        if (requestedTags == null) {
            return allowed;
        }
        for (String raw : requestedTags) {
            String tag = normalizeTag(raw);
            if (tag != null && RecommendThemeTag.fromTag(tag) != null) {
                allowed.add(tag);
            }
        }
        return allowed;
    }

    private static String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (!t.startsWith("#")) {
            t = "#" + t;
        }
        RecommendThemeTag theme = RecommendThemeTag.fromTag(t);
        return theme == null ? null : theme.getTag();
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
