package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * withPet + 태그 검색 병합(RecommendationPipeline.mergePetFirst) 검증 - 2026-08-20까지는 태그가
 * 있으면(themes.isEmpty()==false, "당일치기 시작하기"는 항상 그럼) withPet이 완전히 무시됐다.
 */
class RecommendationPipelineMergeTest {

    @Test
    void petCandidatesComeFirst() {
        RelatedCandidate pet = RelatedCandidate.builder().contentId("PET1").placeName("반려동물동반 카페").build();
        RelatedCandidate themed = RelatedCandidate.builder().contentId("THEME1").placeName("일반 카페").build();

        List<RelatedCandidate> merged = RecommendationPipeline.mergePetFirst(List.of(pet), List.of(themed));

        assertEquals(List.of("PET1", "THEME1"), merged.stream().map(RelatedCandidate::getContentId).toList());
    }

    @Test
    void deduplicatesByContentIdKeepingPetVersion() {
        RelatedCandidate petVersion = RelatedCandidate.builder().contentId("DUP").placeName("펫버전").build();
        RelatedCandidate themedVersion = RelatedCandidate.builder().contentId("DUP").placeName("테마버전").build();

        List<RelatedCandidate> merged = RecommendationPipeline.mergePetFirst(List.of(petVersion), List.of(themedVersion));

        assertEquals(1, merged.size());
        assertEquals("펫버전", merged.get(0).getPlaceName());
    }

    @Test
    void handlesEmptyPetList() {
        RelatedCandidate themed = RelatedCandidate.builder().contentId("THEME1").placeName("일반 카페").build();

        List<RelatedCandidate> merged = RecommendationPipeline.mergePetFirst(List.of(), List.of(themed));

        assertEquals(List.of("THEME1"), merged.stream().map(RelatedCandidate::getContentId).toList());
    }
}
