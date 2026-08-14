package com.windmill.service.recommendation;

import com.windmill.domain.AgeGroup;
import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 연령대 기반 순위 부스트 - CompanionCategoryRanking/AccessibilityRanking과 동일한 설계 원칙
 * (정적 키워드 매핑으로 안정 정렬, 데이터가 없어도 후보를 제거하지 않음).
 *
 * 성인 연령대는 관광공사 API에 조회 조건 자체가 없어(카테고리 텍스트 키워드 매칭 근사) 정적 가중치로만
 * 반영하지만, 동반 자녀 나이는 실제 detailIntro2 체험가능연령(expAgeRange) 필드가 존재해서(라이브 확인,
 * BusinessHoursEvaluator 참고) 그 하한과 자녀 나이를 직접 비교한다 - 다만 이 필드도 대부분 공란이라
 * 여전히 하드 필터가 아닌 순위 부스트로만 쓴다(값이 있고 실제로 자녀 나이보다 높을 때만 뒤로 밀림).
 */
final class AgeGroupRanking {

    private static final Map<AgeGroup, List<String>> ADULT_KEYWORDS = Map.of(
            AgeGroup.TWENTIES, List.of("카페", "핫플레이스", "전망", "쇼핑", "체험"),
            AgeGroup.THIRTIES, List.of("맛집", "카페", "전시", "공원"),
            AgeGroup.FORTIES, List.of("자연", "산책", "둘레길", "전통시장"),
            AgeGroup.FIFTIES, List.of("자연", "전통", "사찰", "둘레길"),
            AgeGroup.SIXTIES, List.of("전통", "사찰", "온천", "휴양"),
            AgeGroup.SEVENTIES_PLUS, List.of("전통", "사찰", "온천", "휴양")
    );

    private AgeGroupRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates, AgeGroup adultAgeGroup, List<Integer> childAges) {
        List<String> adultKeywords = adultAgeGroup == null ? List.of() : ADULT_KEYWORDS.getOrDefault(adultAgeGroup, List.of());
        List<String> childKeywords = youngestChildKeywords(childAges);
        Integer youngestChildAge = youngestAge(childAges);
        if (adultKeywords.isEmpty() && childKeywords.isEmpty() && youngestChildAge == null) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(c -> score(c, adultKeywords, childKeywords, youngestChildAge)))
                .collect(Collectors.toList());
    }

    private static int score(RelatedCandidate c, List<String> adultKeywords, List<String> childKeywords, Integer youngestChildAge) {
        int score = 0;
        // 실제 API 하한 나이가 있고, 그보다 어린 자녀가 있으면 가장 강하게 뒤로 민다(구조화 데이터 우선)
        Integer minAge = BusinessHoursEvaluator.parseMinAge(c.getAgeRangeText());
        if (minAge != null && youngestChildAge != null && youngestChildAge < minAge) {
            score += 3;
        }
        if (!childKeywords.isEmpty() && !matchesAny(c, childKeywords)) {
            score += 1;
        }
        if (!adultKeywords.isEmpty() && !matchesAny(c, adultKeywords)) {
            score += 1;
        }
        return score;
    }

    private static boolean matchesAny(RelatedCandidate c, List<String> keywords) {
        String text = String.join(" ",
                c.getCategoryMcls() == null ? "" : c.getCategoryMcls(),
                c.getCategoryScls() == null ? "" : c.getCategoryScls());
        return keywords.stream().anyMatch(text::contains);
    }

    private static Integer youngestAge(List<Integer> childAges) {
        if (childAges == null || childAges.isEmpty()) {
            return null;
        }
        return childAges.stream().filter(a -> a != null).min(Integer::compareTo).orElse(null);
    }

    /** 가장 어린 자녀의 나이대 키워드 - 가족 활동은 보통 막내 기준으로 정해지므로 최솟값만 사용 */
    private static List<String> youngestChildKeywords(List<Integer> childAges) {
        Integer youngest = youngestAge(childAges);
        if (youngest == null) {
            return List.of();
        }
        if (youngest <= 6) {
            return List.of("키즈카페", "동물원", "워터파크", "테마파크", "체험");
        }
        if (youngest <= 12) {
            return List.of("체험", "테마파크", "동물원", "수족관", "박물관");
        }
        return List.of("액티비티", "체험", "테마파크");
    }
}
