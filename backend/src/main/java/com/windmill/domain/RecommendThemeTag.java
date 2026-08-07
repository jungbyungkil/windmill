package com.windmill.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 추천받기 UI 해시태그 → TourAPI(KorService2) contentType/키워드 매핑.
 * 연관관광지(TarRlteTarService1)만으로는 태그별 결과가 비는 경우가 많아 KorService2로 직접 조회한다.
 */
public enum RecommendThemeTag {
    NATURE("#자연", "자연",
            new int[]{12},
            new String[]{"자연", "공원", "산책", "호수", "계곡", "숲", "해변"}),
    INDOOR("#실내", "실내",
            new int[]{14},
            new String[]{"박물관", "전시관", "미술관", "실내", "갤러리"}),
    FOOD("#맛집", "맛집",
            new int[]{39},
            new String[]{"맛집", "식당", "음식점", "레스토랑"}),
    KIDS("#아이동반", "아이동반",
            new int[]{12, 14, 28},
            new String[]{"키즈", "어린이", "체험", "가족", "아이", "키즈카페"}),
    ACTIVITY("#액티비티", "액티비티",
            new int[]{28, 12},
            new String[]{"레포츠", "체험", "액티비티", "놀이", "스포츠"}),
    HISTORY("#역사", "역사",
            new int[]{14, 12},
            new String[]{"역사", "유적", "기념관", "박물관", "문화재", "향교"});

    private final String tag;
    private final String label;
    private final int[] contentTypeIds;
    private final String[] keywords;

    RecommendThemeTag(String tag, String label, int[] contentTypeIds, String[] keywords) {
        this.tag = tag;
        this.label = label;
        this.contentTypeIds = contentTypeIds;
        this.keywords = keywords;
    }

    public String getTag() {
        return tag;
    }

    public String getLabel() {
        return label;
    }

    public int[] getContentTypeIds() {
        return contentTypeIds;
    }

    public String[] getKeywords() {
        return keywords;
    }

    /** UI에서 고른 태그 + 검색어로 활성 테마 목록을 만든다 (없으면 빈 리스트). */
    public static List<RecommendThemeTag> resolve(List<String> tags, String query) {
        List<RecommendThemeTag> resolved = new ArrayList<>();
        if (tags != null) {
            for (String raw : tags) {
                RecommendThemeTag matched = fromTag(raw);
                if (matched != null && !resolved.contains(matched)) {
                    resolved.add(matched);
                }
            }
        }
        if (resolved.isEmpty() && query != null && !query.isBlank()) {
            RecommendThemeTag fromQuery = fromQuery(query);
            if (fromQuery != null) {
                resolved.add(fromQuery);
            }
        }
        return resolved;
    }

    public static RecommendThemeTag fromTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.startsWith("#") ? raw : "#" + raw;
        for (RecommendThemeTag theme : values()) {
            if (theme.tag.equalsIgnoreCase(normalized) || normalized.contains(theme.label)) {
                return theme;
            }
        }
        return null;
    }

    public static RecommendThemeTag fromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (containsAny(q, "식당", "맛집", "레스토랑", "음식", "밥집", "카페", "커피")) {
            return FOOD;
        }
        if (containsAny(q, "역사", "유적", "기념관", "문화재")) {
            return HISTORY;
        }
        if (containsAny(q, "액티비티", "레포츠", "체험", "스포츠")) {
            return ACTIVITY;
        }
        if (containsAny(q, "아이", "키즈", "어린이", "가족")) {
            return KIDS;
        }
        if (containsAny(q, "실내", "박물관", "전시", "미술관")) {
            return INDOOR;
        }
        if (containsAny(q, "자연", "공원", "산책", "호수", "계곡", "숲", "해변")) {
            return NATURE;
        }
        return null;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
