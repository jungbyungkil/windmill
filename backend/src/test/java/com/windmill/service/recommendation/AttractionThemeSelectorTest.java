package com.windmill.service.recommendation;

import com.windmill.domain.AgeGroup;
import com.windmill.domain.CompanionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "당일치기 시작하기" 관광 테마 선택 - 연령대를 최우선 신호로, 자녀 나이를 그다음으로 반영하는지 검증 */
class AttractionThemeSelectorTest {

    @Test
    void alwaysIncludesIndoorAsFirstTag() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.SOLO, AgeGroup.THIRTIES, List.of());
        assertEquals("#실내", tags.get(0));
    }

    @Test
    void adultAgeGroupDrivesThemeWhenNoChildren_fifties() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.EXTENDED_FAMILY, AgeGroup.FIFTIES, List.of());

        // 대가족이라도 자녀가 없으면(조부모끼리 여행 등) #역사가 무조건 빠지지 않는다 - 연령대(50대)가
        // 실제로 반영돼 #자연/#사찰이 뽑히는지 확인(이번 사용자 제보의 핵심 시나리오)
        assertTrue(tags.contains("#자연"));
        assertTrue(tags.contains("#사찰"));
    }

    @Test
    void adultAgeGroupDrivesThemeWhenNoChildren_twenties() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.COUPLE, AgeGroup.TWENTIES, List.of());

        assertTrue(tags.contains("#카페"));
        assertTrue(tags.contains("#이색거리"));
    }

    @Test
    void childrenPresentAddsKidsTagAndAgeBandTag_toddler() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.EXTENDED_FAMILY, AgeGroup.FORTIES, List.of(4, 8));

        // 막내(4세) 기준으로 유아 테마가 뽑혀야 함 - 40대 성인 취향(#등산트레킹 등)보다 자녀 나이가 우선
        assertTrue(tags.contains("#아이동반"));
        assertTrue(tags.contains("#테마파크"));
    }

    @Test
    void childrenPresentAddsKidsTagAndAgeBandTag_elementary() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.FAMILY_4, AgeGroup.THIRTIES, List.of(9));

        assertTrue(tags.contains("#아이동반"));
        assertTrue(tags.contains("#박물관"));
    }

    @Test
    void childrenPresentAddsKidsTagAndAgeBandTag_teen() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.FAMILY_4, AgeGroup.FORTIES, List.of(15));

        assertTrue(tags.contains("#아이동반"));
        assertTrue(tags.contains("#액티비티"));
    }

    @Test
    void tripleWithChildrenStillUsesChildTheme() {
        // TRIO(3인 여행)도 자녀를 가질 수 있다 - 동반유형이 가족형이 아니어도 자녀 나이 신호가 반영돼야 함
        List<String> tags = AttractionThemeSelector.select(CompanionType.TRIO, AgeGroup.THIRTIES, List.of(5));

        assertTrue(tags.contains("#아이동반"));
        assertTrue(tags.contains("#테마파크"));
    }

    @Test
    void familyCompanionTypeReinforcesKidsTagEvenWithoutChildAges() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.FAMILY_4, AgeGroup.THIRTIES, List.of());

        assertTrue(tags.contains("#아이동반"));
    }

    @Test
    void fallsBackToLegacyBehaviorWhenNoAgeOrChildData() {
        List<String> familyTags = AttractionThemeSelector.select(CompanionType.EXTENDED_FAMILY, null, null);
        assertTrue(familyTags.contains("#자연"));
        assertTrue(familyTags.contains("#아이동반"));

        List<String> nonFamilyTags = AttractionThemeSelector.select(CompanionType.SOLO, null, null);
        assertTrue(nonFamilyTags.contains("#자연"));
        assertTrue(nonFamilyTags.contains("#역사"));
    }

    @Test
    void neverExceedsFourTags() {
        List<String> tags = AttractionThemeSelector.select(CompanionType.EXTENDED_FAMILY, AgeGroup.SIXTIES, List.of(3));
        assertTrue(tags.size() <= 4, "expected at most 4 theme tags, got " + tags.size());
    }

    @Test
    void familyWithKnownAgeGroupAndNoChildrenKeepsBothSignals() {
        // 이번 사용자 제보의 실제 시나리오 - 대가족 + 자녀 나이 미기입 + 성인 연령대만 있는 경우,
        // "#아이동반"과 실제 연령대 태그가 모두 살아남아야 한다(둘 다 4칸 안에 들어감)
        List<String> tags = AttractionThemeSelector.select(CompanionType.EXTENDED_FAMILY, AgeGroup.FIFTIES, List.of());

        assertTrue(tags.contains("#아이동반"));
        assertTrue(tags.contains("#자연"));
        assertTrue(tags.contains("#사찰"));
    }
}
