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
            new String[]{"역사", "유적", "기념관", "박물관", "문화재", "향교"}),

    // 2026-08-16 사용자 요청 - "#맛집"/"#실내"만으로는 뭉뚱그려져서 세분화된 태그를 더 달라고 함.
    // 기존 태그는 그대로 두고(자동 일정 생성 등 기존 흐름 영향 없음), 사용자가 검색 시 추가로 고를
    // 수 있는 세부 태그만 늘렸다.
    // cat1/cat2/cat3는 한국관광공사 categoryCode2를 실제로 조회(2026-08-16)해 확인한 코드다 -
    // 키워드 검색만으론 "사찰"/"온천" 같은 상호명에 없는 단어가 정확도를 떨어뜨려(예: "사찰" 키워드
    // 검색은 엉뚱한 결과가 나왔지만 cat3=A02010800 지정 조회는 실제 사찰만 정확히 나옴) 분류코드
    // 기반 조회를 우선으로 쓰고 키워드 검색은 보조로만 남긴다.
    CAFE("#카페", "카페",
            new int[]{39},
            new String[]{"카페", "커피", "디저트", "베이커리", "브런치"},
            new CategoryCode[]{new CategoryCode("A05", "A0502", "A05020900")}),
    KOREAN_FOOD("#한식", "한식",
            new int[]{39},
            new String[]{"한식", "한정식", "백반", "국밥", "해물"},
            new CategoryCode[]{new CategoryCode("A05", "A0502", "A05020100")}),
    CHINESE_FOOD("#중식", "중식",
            new int[]{39},
            new String[]{"중식", "중국집", "짜장면", "마라"},
            new CategoryCode[]{new CategoryCode("A05", "A0502", "A05020400")}),
    JAPANESE_FOOD("#일식", "일식",
            new int[]{39},
            new String[]{"일식", "스시", "라멘", "돈카츠"},
            new CategoryCode[]{new CategoryCode("A05", "A0502", "A05020300")}),
    WESTERN_FOOD("#양식", "양식",
            new int[]{39},
            new String[]{"양식", "파스타", "스테이크", "피자"},
            new CategoryCode[]{new CategoryCode("A05", "A0502", "A05020200")}),
    MUSEUM("#박물관", "박물관",
            new int[]{14},
            new String[]{"박물관"},
            new CategoryCode[]{new CategoryCode("A02", "A0206", "A02060100")}),
    GALLERY("#미술관", "미술관",
            new int[]{14},
            new String[]{"미술관", "갤러리"},
            new CategoryCode[]{new CategoryCode("A02", "A0206", "A02060500")}),
    EXHIBITION("#전시", "전시",
            new int[]{14},
            new String[]{"전시", "전시회", "특별전", "기획전"},
            new CategoryCode[]{new CategoryCode("A02", "A0206", "A02060300")}),
    ESCAPE_ROOM("#방탈출", "방탈출",
            new int[]{28},
            new String[]{"방탈출", "이스케이프룸", "실내 액티비티"}),
    PALACE("#고궁", "고궁",
            new int[]{12},
            new String[]{"고궁", "궁궐", "성곽", "한옥", "전통"},
            new CategoryCode[]{new CategoryCode("A02", "A0201", "A02010100")}),

    // 2026-08-16 - 사용자가 "예시를 그대로 받아 적지 말고 직접 아이디어를 내보라"고 재요청, 한국관광공사
    // categoryCode2 API를 실제로 조회(cat1=A01~A05)해 데이터가 실제로 존재하는 카테고리 위주로
    // 12종을 새로 골랐다(빈 카테고리를 태그로 만들면 눌러도 결과가 안 나오는 태그가 되어버림).
    TRADITIONAL_MARKET("#전통시장", "전통시장",
            new int[]{38},
            new String[]{"전통시장", "재래시장", "5일장", "상설시장"},
            new CategoryCode[]{
                    new CategoryCode("A04", "A0401", "A04010100"),
                    new CategoryCode("A04", "A0401", "A04010200")}),
    HOT_SPRING("#온천스파", "온천/스파",
            new int[]{12},
            new String[]{"온천", "스파", "찜질방", "족욕"},
            new CategoryCode[]{new CategoryCode("A02", "A0202", "A02020300")}),
    THEME_PARK("#테마파크", "테마파크",
            new int[]{12},
            new String[]{"테마파크", "놀이공원", "워터파크"},
            new CategoryCode[]{new CategoryCode("A02", "A0202", "A02020600")}),
    TEMPLE("#사찰", "사찰",
            new int[]{12},
            new String[]{"사찰", "절", "템플스테이", "산사"},
            new CategoryCode[]{new CategoryCode("A02", "A0201", "A02010800")}),
    TRADITIONAL_EXPERIENCE("#전통체험", "전통체험",
            new int[]{12, 28},
            new String[]{"전통체험", "한복", "민속촌", "농촌체험"},
            new CategoryCode[]{new CategoryCode("A02", "A0203", "A02030200")}),
    CAMPING("#캠핑", "캠핑",
            new int[]{28, 12},
            new String[]{"캠핑", "야영장", "오토캠핑", "글램핑"},
            new CategoryCode[]{new CategoryCode("A03", "A0302", "A03021700")}),
    HIKING("#등산트레킹", "등산·트레킹",
            new int[]{28, 12},
            new String[]{"등산", "트레킹", "둘레길", "암벽등반"},
            new CategoryCode[]{
                    new CategoryCode("A03", "A0302", "A03021800"),
                    new CategoryCode("A03", "A0302", "A03022700")}),
    WATER_SPORTS("#수상레포츠", "수상레포츠",
            new int[]{28},
            new String[]{"수상레포츠", "카약", "래프팅", "스노쿨링", "요트"},
            new CategoryCode[]{new CategoryCode("A03", "A0303", null)}),
    SHOPPING("#쇼핑", "쇼핑",
            new int[]{38},
            new String[]{"쇼핑", "백화점", "아울렛", "면세점"},
            new CategoryCode[]{
                    new CategoryCode("A04", "A0401", "A04010300"),
                    new CategoryCode("A04", "A0401", "A04010400"),
                    new CategoryCode("A04", "A0401", "A04010600")}),
    CRAFT_WORKSHOP("#공방체험", "공방체험",
            new int[]{38, 12},
            new String[]{"공방", "공예체험", "원데이클래스", "도자기"},
            new CategoryCode[]{new CategoryCode("A04", "A0401", "A04010700")}),
    PERFORMANCE_VENUE("#공연장", "공연장",
            new int[]{14},
            new String[]{"공연장", "콘서트", "뮤지컬", "연극"},
            new CategoryCode[]{new CategoryCode("A02", "A0206", "A02060600")}),
    TRENDY_STREET("#이색거리", "이색거리",
            new int[]{12, 38},
            new String[]{"이색거리", "핫플레이스", "골목길", "포토존"},
            new CategoryCode[]{new CategoryCode("A02", "A0203", "A02030600")});

    private final String tag;
    private final String label;
    private final int[] contentTypeIds;
    private final String[] keywords;
    private final CategoryCode[] categoryCodes;

    RecommendThemeTag(String tag, String label, int[] contentTypeIds, String[] keywords) {
        this(tag, label, contentTypeIds, keywords, new CategoryCode[0]);
    }

    RecommendThemeTag(String tag, String label, int[] contentTypeIds, String[] keywords, CategoryCode[] categoryCodes) {
        this.tag = tag;
        this.label = label;
        this.contentTypeIds = contentTypeIds;
        this.keywords = keywords;
        this.categoryCodes = categoryCodes;
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

    /** cat1/cat2/cat3(대/중/소분류) 정밀 조회 코드 - 없으면 빈 배열(키워드/contentTypeId 조회만 사용) */
    public CategoryCode[] getCategoryCodes() {
        return categoryCodes;
    }

    /** areaBasedList2의 cat1/cat2/cat3 정밀 필터 - cat3는 cat2 단위까지만 좁혀도 되는 태그(예: 수상레포츠)는 null 가능 */
    public record CategoryCode(String cat1, String cat2, String cat3) {
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

    /** 더 구체적인 태그(카페·한중일양식·박물관/미술관/전시·고궁·방탈출)를 먼저 검사하고, 그 뒤에 이번엔
     *  더 세분화된 태그로 흡수된 만큼 좁아진 상위 캐치올(맛집/역사/실내)을 검사한다 - 순서가 우선순위다. */
    public static RecommendThemeTag fromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (containsAny(q, "카페", "커피", "디저트", "베이커리", "브런치")) {
            return CAFE;
        }
        if (containsAny(q, "한식", "한정식", "백반", "국밥")) {
            return KOREAN_FOOD;
        }
        if (containsAny(q, "중식", "중국집", "짜장면", "마라")) {
            return CHINESE_FOOD;
        }
        if (containsAny(q, "일식", "스시", "라멘", "돈카츠")) {
            return JAPANESE_FOOD;
        }
        if (containsAny(q, "양식", "파스타", "스테이크", "피자")) {
            return WESTERN_FOOD;
        }
        if (containsAny(q, "식당", "맛집", "레스토랑", "음식", "밥집")) {
            return FOOD;
        }
        if (containsAny(q, "고궁", "궁궐", "경복궁", "창덕궁", "덕수궁", "성곽")) {
            return PALACE;
        }
        if (containsAny(q, "역사", "유적", "기념관", "문화재")) {
            return HISTORY;
        }
        if (containsAny(q, "박물관")) {
            return MUSEUM;
        }
        if (containsAny(q, "미술관", "갤러리")) {
            return GALLERY;
        }
        if (containsAny(q, "전시", "전시회", "특별전", "기획전")) {
            return EXHIBITION;
        }
        if (containsAny(q, "방탈출", "이스케이프룸")) {
            return ESCAPE_ROOM;
        }
        if (containsAny(q, "전통시장", "재래시장", "5일장", "상설시장")) {
            return TRADITIONAL_MARKET;
        }
        if (containsAny(q, "온천", "스파", "찜질방", "족욕")) {
            return HOT_SPRING;
        }
        if (containsAny(q, "테마파크", "놀이공원", "워터파크")) {
            return THEME_PARK;
        }
        if (containsAny(q, "사찰", "템플스테이", "산사")) {
            return TEMPLE;
        }
        if (containsAny(q, "전통체험", "한복", "민속촌", "농촌체험")) {
            return TRADITIONAL_EXPERIENCE;
        }
        if (containsAny(q, "캠핑", "야영장", "오토캠핑", "글램핑")) {
            return CAMPING;
        }
        if (containsAny(q, "등산", "트레킹", "둘레길", "암벽등반")) {
            return HIKING;
        }
        if (containsAny(q, "수상레포츠", "카약", "래프팅", "스노쿨링", "요트")) {
            return WATER_SPORTS;
        }
        if (containsAny(q, "쇼핑", "백화점", "아울렛", "면세점")) {
            return SHOPPING;
        }
        if (containsAny(q, "공방", "공예체험", "원데이클래스", "도자기")) {
            return CRAFT_WORKSHOP;
        }
        if (containsAny(q, "공연장", "콘서트", "뮤지컬", "연극")) {
            return PERFORMANCE_VENUE;
        }
        if (containsAny(q, "이색거리", "핫플레이스", "골목길", "포토존")) {
            return TRENDY_STREET;
        }
        if (containsAny(q, "액티비티", "레포츠", "체험", "스포츠")) {
            return ACTIVITY;
        }
        if (containsAny(q, "아이", "키즈", "어린이", "가족")) {
            return KIDS;
        }
        if (containsAny(q, "실내")) {
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
