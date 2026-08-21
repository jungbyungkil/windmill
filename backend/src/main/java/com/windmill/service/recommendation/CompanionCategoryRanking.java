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
 * 당기는 안정 정렬(stable sort)이라, 같은 그룹 내에서는 Stage1·개인화 순위가 그대로 유지된다.
 *
 * ⚠ 알려진 한계(2026-08-20): "당일치기 시작하기"가 쓰는 태그 검색 경로
 * (Stage1RelatedAttractionService.fetchByThemes → mapKorItems)는 categoryMcls/categoryScls를 채우지
 * 않아(KorService2 areaBasedList2/searchKeyword2는 이 필드 자체가 없음 - cat1/2/3 코드만 반환) 이
 * 경로에서는 사실상 무동작이다(AgeGroupRanking의 키워드 매칭도 동일 한계). cat1/2/3 코드→한글명
 * 역매핑 테이블을 새로 만드는 대신, 더 상위 레버인 AttractionThemeSelector(어떤 태그로 검색할지
 * 자체를 연령대/동반유형/자녀나이로 결정)로 같은 목적을 더 낮은 리스크로 달성했다 - 이 클래스는
 * seedPlaceName 기반 경로(AnchorPlanService 등, categoryMcls/Scls가 실제로 채워짐)에서는 계속
 * 정상 동작한다.
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
