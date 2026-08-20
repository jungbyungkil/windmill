package com.windmill.service.recommendation;

import com.windmill.domain.AgeGroup;
import com.windmill.domain.CompanionType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "당일치기 시작하기"(표준 4단계 일정)의 관광 슬롯 테마 선택 - 기존엔 대가족이냐 아니냐 이진 분기뿐
 * (#실내/#자연/#아이동반 vs #실내/#자연/#역사)이라 연령대(adultAgeGroup)를 전혀 안 썼다. 이러면
 * 역사 테마를 좋아할 법한 대가족도 무조건 #역사가 배제돼 근대역사관류가 애초에 후보 풀에 못 들어온다
 * (2026-08-20 사용자 제보 - 목포 대가족 여행에 근대역사관 대신 남농기념관이 뽑힌 사례).
 *
 * 연령대(있으면)를 1순위 신호로, 동반 자녀 유무를 2순위 신호로 써서 실제 RecommendThemeTag 28종 중
 * 존재하는 태그로만 매핑한다 - AgeGroupRanking.ADULT_KEYWORDS/youngestChildKeywords와 같은 의도를
 * "후보를 재정렬"이 아니라 "애초에 어떤 태그로 검색할지"에 반영해, categoryMcls/categoryScls가 비어
 * 있어 사실상 무동작인 테마 검색 경로의 다운스트림 랭킹(CompanionCategoryRanking/AgeGroupRanking)에
 * 기대지 않고도 확실히 개인화가 반영되게 한다.
 */
final class AttractionThemeSelector {

    /** 기존엔 3개(#실내 고정 1 + 나머지 2)였다 - 가족형+연령대 둘 다 실제 신호가 있는 가장 흔한
     *  케이스(예: 대가족+50대)에서 "#아이동반"과 연령대 태그 2개가 모두 살아남게 하려면 1개가
     *  더 필요하다. Stage1은 테마별 조회를 동시(concurrent)로 돌리므로(fetchByThemes 참고) 체감
     *  지연은 테마 수만큼 선형으로 늘지 않는다. */
    private static final int MAX_THEMES = 4;

    private AttractionThemeSelector() {
    }

    static List<String> select(CompanionType companionType, AgeGroup adultAgeGroup, List<Integer> childAges) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("#실내"); // 날씨/휴무 대체 여지 확보 - 기존 동작 유지

        boolean familyPace = SmartPlanService.isFamilyPace(companionType);
        if (familyPace) {
            // 자녀 나이를 안 적었어도 동반유형 자체가 가족형이면 아이동반 맥락을 먼저 확보해둔다 -
            // 뒤에서 연령대 태그가 추가돼도 자리 경쟁에서 밀려 잘리지 않게 순서를 앞에 둔다.
            tags.add("#아이동반");
        }

        Integer youngestChildAge = youngestAge(childAges);
        if (youngestChildAge != null) {
            tags.add("#아이동반");
            tags.add(childThemeTag(youngestChildAge));
        } else if (adultAgeGroup != null) {
            tags.addAll(adultThemeTags(adultAgeGroup));
        } else if (!familyPace) {
            // 연령대·자녀 나이 둘 다 모르고 가족형도 아니면(신규 데이터 없는 옛 일정 등) 기존 동작으로 폴백
            tags.add("#자연");
            tags.add("#역사");
        } else {
            // 가족형인데 연령대·자녀 나이 데이터가 전혀 없으면 기존 동작(자연+아이동반)과 동일하게 유지
            tags.add("#자연");
        }

        return tags.stream().limit(MAX_THEMES).collect(Collectors.toList());
    }

    private static List<String> adultThemeTags(AgeGroup adultAgeGroup) {
        return switch (adultAgeGroup) {
            case TWENTIES -> List.of("#카페", "#이색거리");
            case THIRTIES -> List.of("#카페", "#전시");
            case FORTIES -> List.of("#자연", "#등산트레킹");
            case FIFTIES -> List.of("#자연", "#사찰");
            case SIXTIES, SEVENTIES_PLUS -> List.of("#사찰", "#온천스파");
        };
    }

    /** 막내 나이대별 1개 - RecommendThemeTag 28종 중 실제 존재하는 태그로만 매핑(동물원/수족관 등은 별도 태그 없음) */
    private static String childThemeTag(int youngestChildAge) {
        if (youngestChildAge <= 6) {
            return "#테마파크";
        }
        if (youngestChildAge <= 12) {
            return "#박물관";
        }
        return "#액티비티";
    }

    private static Integer youngestAge(List<Integer> childAges) {
        if (childAges == null || childAges.isEmpty()) {
            return null;
        }
        return childAges.stream().filter(a -> a != null).min(Integer::compareTo).orElse(null);
    }
}
