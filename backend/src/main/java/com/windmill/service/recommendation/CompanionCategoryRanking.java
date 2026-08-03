package com.windmill.service.recommendation;

import com.windmill.domain.CompanionType;
import com.windmill.dto.RelatedCandidate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 동반유형별 카테고리 가중치 - LLM 없이 정적 키워드 매핑으로 후보 순서를 보정한다.
 * TarRlteTarService1의 categoryMcls/categoryScls(한글 분류명) 안에 선호 키워드가 포함된 후보를 앞으로
 * 당기는 안정 정렬(stable sort)이라, 같은 그룹 내에서는 Stage3가 정한 여유율 순서가 그대로 유지된다.
 */
final class CompanionCategoryRanking {

    private static final Map<CompanionType, List<String>> PREFERRED_KEYWORDS = Map.of(
            CompanionType.FAMILY_4, List.of("체험", "자연", "테마파크", "동물원", "수족관"),
            CompanionType.EXTENDED_FAMILY, List.of("자연", "휴양", "전통", "온천"),
            CompanionType.COUPLE, List.of("카페", "야경", "전망", "쇼핑"),
            CompanionType.SOLO, List.of()
    );

    private CompanionCategoryRanking() {
    }

    static List<RelatedCandidate> rank(List<RelatedCandidate> candidates, CompanionType companionType) {
        List<String> keywords = companionType == null ? List.of() : PREFERRED_KEYWORDS.getOrDefault(companionType, List.of());
        if (keywords.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator.comparing(c -> matchesPreferred(c, keywords) ? 0 : 1))
                .collect(Collectors.toList());
    }

    private static boolean matchesPreferred(RelatedCandidate c, List<String> keywords) {
        String text = String.join(" ",
                c.getCategoryMcls() == null ? "" : c.getCategoryMcls(),
                c.getCategoryScls() == null ? "" : c.getCategoryScls());
        return keywords.stream().anyMatch(text::contains);
    }
}
