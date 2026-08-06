package com.windmill.service.recommendation;

import com.windmill.domain.PlaceCategory;
import com.windmill.dto.CategoryPlaceGroup;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RegionCode;
import com.windmill.dto.TourAttractionSummary;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.service.trigger.TriggerScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DB 없이 TourAPI + 집중률(방문자 추이) API로 카테고리별 장소를 구성한다.
 * 정렬 우선순위: 혼잡도(집중률) 낮은 순(여유로운 곳) → 썸네일 있는 곳 → 이름.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryRecommendationService {

    private static final int MIN_PER_CATEGORY = 3;
    private static final int TARGET_PER_CATEGORY = 6;

    private final TourAttractionService tourAttractionService;
    private final RegionCodeService regionCodeService;
    private final TriggerScheduler triggerScheduler;

    public Mono<List<CategoryPlaceGroup>> recommendByCategory(String regionCode, Set<String> excludeContentIds) {
        RegionCode region = regionCodeService.find(regionCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + regionCode));
        Set<String> exclude = excludeContentIds == null ? Set.of() : excludeContentIds;

        return triggerScheduler.ensureFresh(region)
                .onErrorReturn(RegionCondition.builder().crowdRateByPlaceName(Map.of()).build())
                .flatMap(condition -> Flux.fromArray(PlaceCategory.values())
                        .concatMap(category -> buildGroup(category, region, condition, exclude))
                        .collectList());
    }

    private Mono<CategoryPlaceGroup> buildGroup(PlaceCategory category, RegionCode region,
                                                  RegionCondition condition, Set<String> exclude) {
        return fetchCandidates(category, region)
                .map(summaries -> {
                    List<RecommendationCandidate> places = summaries.stream()
                            .filter(s -> s.getContentId() != null && !exclude.contains(s.getContentId()))
                            .filter(s -> matchesCategory(category, s))
                            .map(s -> toCandidate(s, category, condition))
                            .sorted(visitorPriorityComparator())
                            .limit(TARGET_PER_CATEGORY)
                            .collect(Collectors.toCollection(ArrayList::new));

                    // 최소 3건 보장: 필터가 너무 빡세면 제외 키워드만 푼 후보로 보충
                    if (places.size() < MIN_PER_CATEGORY) {
                        Set<String> already = places.stream()
                                .map(RecommendationCandidate::getContentId)
                                .collect(Collectors.toSet());
                        summaries.stream()
                                .filter(s -> s.getContentId() != null && !exclude.contains(s.getContentId()))
                                .filter(s -> !already.contains(s.getContentId()))
                                .filter(s -> !shouldExcludeTitle(category, s.getTitle()))
                                .map(s -> toCandidate(s, category, condition))
                                .sorted(visitorPriorityComparator())
                                .forEach(c -> {
                                    if (places.size() < MIN_PER_CATEGORY) {
                                        places.add(c);
                                    }
                                });
                    }

                    for (int i = 0; i < places.size(); i++) {
                        places.get(i).setRank(i + 1);
                    }

                    log.info("[CategoryReco] {} {}건 (region={})", category.getLabel(), places.size(), region.getSignguName());
                    return CategoryPlaceGroup.builder()
                            .category(category)
                            .label(category.getLabel())
                            .subLabel(category.getSubLabel())
                            .places(places)
                            .build();
                });
    }

    private Mono<List<TourAttractionSummary>> fetchCandidates(PlaceCategory category, RegionCode region) {
        String regn = region.getLDongRegnCd();
        String signgu = region.getLDongSignguCd();

        if (category == PlaceCategory.KIDS_CAFE) {
            return Flux.fromArray(category.getSearchKeywords())
                    .distinct()
                    .concatMap(keyword -> tourAttractionService.searchByKeyword(keyword, null, regn, signgu)
                            .onErrorReturn(List.of()))
                    .collectList()
                    .map(this::dedupeByContentId);
        }

        if (category.getContentTypeId() != null && category.getSearchKeywords().length > 0) {
            // 음식점/카페: contentType + 키워드 병행 후 합치기
            Mono<List<TourAttractionSummary>> byType = tourAttractionService
                    .listByRegion(category.getContentTypeId(), regn, signgu)
                    .onErrorReturn(List.of());
            Mono<List<TourAttractionSummary>> byKeyword = Flux.fromArray(category.getSearchKeywords())
                    .concatMap(keyword -> tourAttractionService
                            .searchByKeyword(keyword, category.getContentTypeId(), regn, signgu)
                            .onErrorReturn(List.of()))
                    .collectList()
                    .map(this::dedupeByContentId);
            return Mono.zip(byType, byKeyword)
                    .map(tuple -> dedupeByContentId(List.of(tuple.getT1(), tuple.getT2())));
        }

        if (category.getContentTypeId() != null) {
            return tourAttractionService.listByRegion(category.getContentTypeId(), regn, signgu)
                    .onErrorReturn(List.of());
        }

        return Flux.fromArray(category.getSearchKeywords())
                .concatMap(keyword -> tourAttractionService.searchByKeyword(keyword, null, regn, signgu)
                        .onErrorReturn(List.of()))
                .collectList()
                .map(this::dedupeByContentId);
    }

    private List<TourAttractionSummary> dedupeByContentId(List<List<TourAttractionSummary>> batches) {
        Map<String, TourAttractionSummary> byId = new LinkedHashMap<>();
        for (List<TourAttractionSummary> batch : batches) {
            for (TourAttractionSummary s : batch) {
                if (s.getContentId() != null && !byId.containsKey(s.getContentId())) {
                    byId.put(s.getContentId(), s);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private boolean matchesCategory(PlaceCategory category, TourAttractionSummary summary) {
        if (shouldExcludeTitle(category, summary.getTitle())) {
            return false;
        }
        String title = summary.getTitle() == null ? "" : summary.getTitle();
        if (category.getSearchKeywords().length == 0) {
            return true;
        }
        // 박물관/키즈카페: 키워드가 제목에 있거나 contentType이 맞으면 통과
        if (category == PlaceCategory.MUSEUM) {
            Integer type = parseType(summary.getContentTypeId());
            return type != null && type == 14
                    || containsAny(title, category.getSearchKeywords());
        }
        if (category == PlaceCategory.RESTAURANT) {
            // 음식점 타입이면 카페 제외 후 통과. 제목에 식당 계열 키워드가 있으면 가산
            Integer type = parseType(summary.getContentTypeId());
            return type != null && type == 39;
        }
        if (category == PlaceCategory.CAFE) {
            return containsAny(title, category.getSearchKeywords());
        }
        return containsAny(title, category.getSearchKeywords());
    }

    private boolean shouldExcludeTitle(PlaceCategory category, String title) {
        if (title == null || category.getExcludeTitleKeywords().length == 0) {
            return false;
        }
        return containsAny(title, category.getExcludeTitleKeywords());
    }

    private RecommendationCandidate toCandidate(TourAttractionSummary s, PlaceCategory category,
                                                  RegionCondition condition) {
        Double crowdRate = condition.getCrowdRate(s.getTitle());
        Integer typeId = parseType(s.getContentTypeId());
        String thumb = blankToNull(s.getFirstImage());
        if (thumb == null) {
            thumb = blankToNull(s.getFirstImage2());
        }
        return RecommendationCandidate.builder()
                .contentId(s.getContentId())
                .contentTypeId(typeId)
                .placeName(s.getTitle())
                .category(category.getLabel())
                .thumbnailUrl(thumb)
                .crowdRate(crowdRate)
                .freeRatePercent(crowdRate == null ? null : 100.0 - crowdRate)
                .matchedTags(defaultTags(category))
                .oneLiner(oneLiner(category, s.getTitle(), crowdRate))
                .addr1(s.getAddr1())
                .tel(s.getTel())
                .rank(0)
                .build();
    }

    private List<String> defaultTags(PlaceCategory category) {
        return switch (category) {
            case RESTAURANT -> List.of("#맛집");
            case MUSEUM -> List.of("#실내", "#역사");
            case KIDS_CAFE -> List.of("#아이동반", "#실내");
            case CAFE -> List.of("#실내");
        };
    }

    private String oneLiner(PlaceCategory category, String placeName, Double crowdRate) {
        if (crowdRate != null && crowdRate < 50) {
            return placeName + " · 지금 여유로워요 (혼잡 " + Math.round(crowdRate) + "%)";
        }
        if (crowdRate != null) {
            return placeName + " · 여유율 " + Math.round(100 - crowdRate) + "% " + category.getSubLabel();
        }
        return category.getSubLabel() + "으로 추천하는 " + placeName;
    }

    /** 방문자(집중률) 낮은 순 = 여유로운 곳 우선 → 썸네일 있는 곳 → 이름 */
    private Comparator<RecommendationCandidate> visitorPriorityComparator() {
        return Comparator
                .comparing((RecommendationCandidate c) -> c.getCrowdRate() == null ? Double.POSITIVE_INFINITY : c.getCrowdRate())
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0)
                .thenComparing(c -> c.getPlaceName() == null ? "" : c.getPlaceName());
    }

    private Integer parseType(String contentTypeId) {
        if (contentTypeId == null || contentTypeId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(contentTypeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean containsAny(String text, String[] keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
