package com.windmill.service.recommendation;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.PlaceCategory;
import com.windmill.dto.AnchorPlanRequest;
import com.windmill.dto.CategoryPlaceGroup;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.PlaceTagSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 앵커(고정 일정) 등록 - 예: "DDP에서 19:00 공연"처럼 이미 시각이 정해진 장소를 하루 일정의 기준점으로
 * 삼고, 그 앞뒤 빈 시간대를 채운다. AI가 코스를 통째로 짜는 SmartPlan과 달리 "사용자가 이미 정한 한
 * 곳+시각"이 기준점이라, 채워 넣는 후보는 관광공사 TarRlteTarService1(연관 관광지)로 앵커 장소 자체를
 * seed로 조회한 "그 장소 근처의 실제 연관 명소"만 사용한다(독립적인 태그 검색이 아님 - 사용자 요구사항).
 *
 * 공연 전(오전~공연 전): 점심 식사 + 가벼운 도보 관광지
 * 공연 후: 저녁 식사 + (선택) 분위기 좋은 카페/야경 명소
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnchorPlanService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final LocalTime LUNCH_TIME = LocalTime.of(12, 0);
    private static final LocalTime EVENING_FLOOR = LocalTime.of(18, 0);
    private static final int LUNCH_MINUTES = 60;
    private static final int WALK_MINUTES = 60;
    /** 공연/전시 평균 관람시간 가정치 - 사용자가 종료 시각까지 입력하진 않아 "공연 후" 슬롯 기준점 계산용 */
    private static final int ASSUMED_EVENT_MINUTES = 120;

    private final RecommendationPipeline recommendationPipeline;
    private final CategoryRecommendationService categoryRecommendationService;

    public Mono<List<RecommendationCandidate>> buildPlan(Itinerary itinerary, AnchorPlanRequest request) {
        RecommendationCandidate anchor = request.getAnchor();
        if (anchor == null || anchor.getContentId() == null) {
            return Mono.error(new IllegalArgumentException("목적지 정보가 없습니다"));
        }
        LocalTime anchorTime = ClosingTimeGate.parseHhMm(request.getAnchorTime());
        if (anchorTime == null) {
            return Mono.error(new IllegalArgumentException("공연/이벤트 시작 시각을 입력해주세요 (예: 19:00)"));
        }
        anchor.setSuggestedTime(anchorTime.format(TIME_FORMAT));
        anchor.setOneLiner(withSlotNote(anchor.getOneLiner(), "직접 등록한 고정 일정"));
        LocalTime anchorEnd = anchorTime.plusMinutes(ASSUMED_EVENT_MINUTES);

        Set<String> used = new HashSet<>(existingContentIds(itinerary));
        used.add(anchor.getContentId());

        RecommendationRequest relatedReq = RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
                .seedPlaceName(anchor.getPlaceName())
                .excludeContentIds(List.copyOf(used))
                .originContentId(anchor.getContentId())
                .originContentTypeId(anchor.getContentTypeId())
                // 표준 4단계 일정과 동일하게 "당일치기 시작하기" 첫 화면 스피드가 중요해 LLM(Stage4)
                // 단계를 건너뛴다 - 순위 결정에는 관여하지 않아 뽑히는 장소는 동일함(SmartPlanService 참고)
                .skipLlm(true)
                .build();

        // TarRlteTarService1(연관 관광지)는 seed(앵커 장소)에 baseYm 데이터가 아예 없을 수 있다 -
        // 라이브 검증(2026-08-14, DDP)에서 실제로 "최근 3개월간 데이터 미발행"으로 0건이 나옴을 확인함.
        // 지역 카테고리 추천(식당/박물관/카페, 앵커 이름과 무관하게 지역 전체 기준)을 항상 함께 조회해
        // 연관 관광지가 비어도 조용히 카테고리 추천으로 보강한다.
        Mono<List<RecommendationCandidate>> relatedMono = recommendationPipeline.recommend(relatedReq)
                .onErrorReturn(List.of());
        Mono<List<CategoryPlaceGroup>> categoryMono = categoryRecommendationService
                .recommendByCategory(itinerary.getSignguFullCode(), used, itinerary.getChildAges())
                .onErrorReturn(List.of());

        return Mono.zip(relatedMono, categoryMono)
                .map(tuple -> {
                    List<RecommendationCandidate> related = tuple.getT1();
                    List<CategoryPlaceGroup> categoryGroups = tuple.getT2();

                    List<RecommendationCandidate> foodPool = new ArrayList<>();
                    List<RecommendationCandidate> sightPool = new ArrayList<>();
                    for (RecommendationCandidate c : related) {
                        if (c.getContentId() == null || used.contains(c.getContentId())) {
                            continue;
                        }
                        (isFood(c) ? foodPool : sightPool).add(c);
                    }
                    sortByDistance(foodPool);
                    sortByDistance(sightPool);
                    appendCategoryFallback(foodPool, categoryGroups, PlaceCategory.RESTAURANT, used);
                    appendCategoryFallback(sightPool, categoryGroups, PlaceCategory.MUSEUM, used);

                    List<RecommendationCandidate> out = new ArrayList<>();
                    out.add(anchor);

                    // 공연 전: 점심(공연보다 최소 100분 전이어야 자연스러움) → 여유 있으면 가벼운 도보 관광지
                    if (anchorTime.isAfter(LUNCH_TIME.plusMinutes(LUNCH_MINUTES + 40))) {
                        RecommendationCandidate lunch = takeNearest(foodPool, used);
                        if (lunch != null) {
                            tag(lunch, LUNCH_TIME, "공연 전 점심 식사로 추천");
                            out.add(lunch);
                            LocalTime walkTime = LUNCH_TIME.plusMinutes(LUNCH_MINUTES);
                            if (anchorTime.isAfter(walkTime.plusMinutes(WALK_MINUTES + 30))) {
                                RecommendationCandidate walk = takeNearest(sightPool, used);
                                if (walk != null) {
                                    tag(walk, walkTime, "공연 전 가볍게 둘러보기 좋은 곳");
                                    out.add(walk);
                                }
                            }
                        }
                    }

                    // 공연 후: 저녁 식사
                    LocalTime dinnerTime = laterOf(anchorEnd, EVENING_FLOOR);
                    RecommendationCandidate dinner = takeNearest(foodPool, used);
                    if (dinner != null) {
                        tag(dinner, dinnerTime, "공연 후 저녁 식사로 추천");
                        out.add(dinner);
                    }

                    // 공연 후 선택지: 분위기 좋은 카페/야경 명소
                    RecommendationCandidate cafe = pickFromCategory(categoryGroups, PlaceCategory.CAFE, used);
                    if (cafe != null) {
                        tag(cafe, dinnerTime, "공연 후 분위기 좋은 카페·야경 명소로 추천 (선택)");
                        out.add(cafe);
                    }
                    return out;
                })
                .doOnNext(list -> log.info("[AnchorPlan] anchor='{}' anchorTime={} 채움 {}건",
                        anchor.getPlaceName(), anchorTime, list.size()));
    }

    private boolean isFood(RecommendationCandidate c) {
        return PlaceTagSanitizer.looksLikeFood(c.getContentTypeId(), c.getPlaceName(), c.getCategory());
    }

    /** 앵커와의 거리(distanceKm, Stage1에서 origin 기준으로 이미 계산됨) 가까운 순 정렬 */
    private void sortByDistance(List<RecommendationCandidate> pool) {
        pool.sort(Comparator.comparing(c -> c.getDistanceKm() == null ? Double.MAX_VALUE : c.getDistanceKm()));
    }

    /** 연관 관광지 풀이 비어 있을 때만(부분 보강은 안 함 - 가까운 연관 후보가 있으면 그게 항상 우선) 지역 카테고리로 채운다 */
    private void appendCategoryFallback(List<RecommendationCandidate> pool, List<CategoryPlaceGroup> groups,
                                         PlaceCategory category, Set<String> used) {
        if (!pool.isEmpty()) {
            return;
        }
        groups.stream()
                .filter(g -> g.getCategory() == category)
                .findFirst()
                .ifPresent(g -> g.getPlaces().stream()
                        .filter(c -> c.getContentId() != null && !used.contains(c.getContentId()))
                        .forEach(pool::add));
    }

    private RecommendationCandidate pickFromCategory(List<CategoryPlaceGroup> groups, PlaceCategory category, Set<String> used) {
        return groups.stream()
                .filter(g -> g.getCategory() == category)
                .findFirst()
                .flatMap(g -> g.getPlaces().stream()
                        .filter(c -> c.getContentId() != null && !used.contains(c.getContentId()))
                        .findFirst())
                .orElse(null);
    }

    private RecommendationCandidate takeNearest(List<RecommendationCandidate> pool, Set<String> used) {
        for (int i = 0; i < pool.size(); i++) {
            RecommendationCandidate c = pool.get(i);
            if (!used.contains(c.getContentId())) {
                pool.remove(i);
                used.add(c.getContentId());
                return c;
            }
        }
        return null;
    }

    private void tag(RecommendationCandidate c, LocalTime time, String note) {
        c.setSuggestedTime(time.format(TIME_FORMAT));
        c.setOneLiner(withSlotNote(c.getOneLiner(), note));
    }

    private LocalTime laterOf(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private String withSlotNote(String existing, String note) {
        return (existing == null || existing.isBlank()) ? note : note + " · " + existing;
    }

    private List<String> existingContentIds(Itinerary itinerary) {
        return itinerary.getItems().stream()
                .map(ItineraryItem::getContentId)
                .collect(Collectors.toList());
    }
}
