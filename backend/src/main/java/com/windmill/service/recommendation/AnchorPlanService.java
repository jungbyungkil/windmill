package com.windmill.service.recommendation;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.PlaceCategory;
import com.windmill.domain.RecommendThemeTag;
import com.windmill.dto.AnchorPlanRequest;
import com.windmill.dto.DaySlot;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 목적지 직접 선택 플로우 - 사용자가 고른 장소(anchor)를 오전/오후 슬롯에 배치하고, 그 앞뒤 빈 시간대를
 * 채운다. AI가 코스를 통째로 만드는 SmartPlan/AutoPlan과 달리 "사용자가 이미 정한 한 곳"을 기준점으로
 * 삼는 게 핵심이라, 채워 넣는 후보들은 전부 이 기준점(anchor)에서 가까운 순으로 4단계 파이프라인을
 * 재사용해 고른다(별도 추천 로직을 새로 만들지 않음).
 *
 * 오전 배치: anchor → 점심(맛집) → 주변 전시관·박물관 → 저녁 식사/카페
 * 오후 배치: anchor → 저녁 식사/카페 (점심은 이미 지났다고 가정)
 *
 * ⚠ 점심/전시관/저녁을 Mono.zip으로 동시에 조회하면 더 빠를 것 같지만 실제로는 더 느려진다 -
 * data.go.kr가 동시 요청에 취약해(Stage2가 이미 EXTERNAL_CALL_CONCURRENCY=4로 자기 안에서 제한하는
 * 이유와 동일) 파이프라인 3~4개를 겹쳐 부르면 ReadTimeoutException이 무더기로 나는 걸 라이브
 * 호출로 확인함(2026-08-14). 그래서 순차 호출을 유지하고, 대신 컨트롤러 쪽 비동기 타임아웃을
 * 늘려서(WebConfig) 순차 호출의 누적 시간(호출당 15~20초대 × 최대 4회)을 버틸 여유를 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnchorPlanService {

    private static final LocalTime MORNING_START = LocalTime.of(9, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(14, 0);
    private static final LocalTime LUNCH_FLOOR = LocalTime.of(12, 0);
    private static final LocalTime EVENING_FLOOR = LocalTime.of(18, 0);
    private static final int LUNCH_MINUTES = 60;
    private static final int MUSEUM_MINUTES = 90;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationPipeline recommendationPipeline;
    private final CategoryRecommendationService categoryRecommendationService;

    public Mono<List<RecommendationCandidate>> buildPlan(Itinerary itinerary, AnchorPlanRequest request) {
        RecommendationCandidate anchor = request.getAnchor();
        if (anchor == null || anchor.getContentId() == null) {
            return Mono.error(new IllegalArgumentException("목적지 정보가 없습니다"));
        }
        DaySlot slot = request.getSlot() == null ? DaySlot.MORNING : request.getSlot();
        int duration = Math.max(request.getDurationMinutes(), 30);
        LocalTime start = slot == DaySlot.MORNING ? MORNING_START : AFTERNOON_START;
        anchor.setSuggestedTime(start.format(TIME_FORMAT));
        anchor.setOneLiner(withSlotNote(anchor.getOneLiner(), "직접 선택한 장소"));

        PlanState state = new PlanState();
        state.items.add(anchor);
        state.used.addAll(existingContentIds(itinerary));
        state.used.add(anchor.getContentId());
        state.anchorContentId = anchor.getContentId();
        state.anchorContentTypeId = anchor.getContentTypeId();
        state.lastContentId = anchor.getContentId();
        state.lastContentTypeId = anchor.getContentTypeId();
        state.cursor = start.plusMinutes(duration);

        Mono<PlanState> chain = Mono.just(state);
        if (slot == DaySlot.MORNING) {
            chain = chain.flatMap(s -> appendLunch(itinerary, s))
                    .flatMap(s -> appendMuseum(itinerary, s));
        }
        return chain
                .flatMap(s -> appendDinner(itinerary, s))
                .flatMap(s -> appendCafe(itinerary, s))
                .map(s -> s.items)
                .doOnNext(list -> log.info("[AnchorPlan] anchor='{}' slot={} 채움 {}건",
                        anchor.getPlaceName(), slot, list.size()));
    }

    private Mono<PlanState> appendLunch(Itinerary itinerary, PlanState state) {
        return fetchNearest(itinerary, RecommendThemeTag.FOOD, state.anchorContentId, state.anchorContentTypeId, state.used)
                .map(lunch -> {
                    LocalTime time = laterOf(state.cursor, LUNCH_FLOOR);
                    lunch.setSuggestedTime(time.format(TIME_FORMAT));
                    lunch.setOneLiner(withSlotNote(lunch.getOneLiner(), "점심 식사로 추천"));
                    state.items.add(lunch);
                    state.used.add(lunch.getContentId());
                    state.cursor = time.plusMinutes(LUNCH_MINUTES);
                    return state;
                })
                .defaultIfEmpty(state);
    }

    private Mono<PlanState> appendMuseum(Itinerary itinerary, PlanState state) {
        return fetchNearest(itinerary, RecommendThemeTag.INDOOR, state.anchorContentId, state.anchorContentTypeId, state.used)
                .map(museum -> {
                    museum.setSuggestedTime(state.cursor.format(TIME_FORMAT));
                    museum.setOneLiner(withSlotNote(museum.getOneLiner(), "주변 전시관·박물관"));
                    state.items.add(museum);
                    state.used.add(museum.getContentId());
                    state.lastContentId = museum.getContentId();
                    state.lastContentTypeId = museum.getContentTypeId();
                    state.cursor = state.cursor.plusMinutes(MUSEUM_MINUTES);
                    return state;
                })
                .defaultIfEmpty(state);
    }

    private Mono<PlanState> appendDinner(Itinerary itinerary, PlanState state) {
        return fetchNearest(itinerary, RecommendThemeTag.FOOD, state.lastContentId, state.lastContentTypeId, state.used)
                .map(dinner -> {
                    LocalTime time = laterOf(state.cursor, EVENING_FLOOR);
                    dinner.setSuggestedTime(time.format(TIME_FORMAT));
                    dinner.setOneLiner(withSlotNote(dinner.getOneLiner(), "저녁 식사로 추천"));
                    state.items.add(dinner);
                    state.used.add(dinner.getContentId());
                    state.eveningTime = time;
                    return state;
                })
                .defaultIfEmpty(state);
    }

    /** 저녁 식사와 별개 선택지 - "분위기 좋은 카페"는 CategoryRecommendationService(여유율 우선 정렬)에서 고른다 */
    private Mono<PlanState> appendCafe(Itinerary itinerary, PlanState state) {
        return categoryRecommendationService.recommendByCategory(itinerary.getSignguFullCode(), state.used)
                .flatMap(groups -> groups.stream()
                        .filter(g -> g.getCategory() == PlaceCategory.CAFE)
                        .findFirst()
                        .flatMap(g -> g.getPlaces().stream()
                                .filter(c -> !state.used.contains(c.getContentId()))
                                .findFirst())
                        .map(Mono::just)
                        .orElseGet(Mono::empty))
                .map(cafe -> {
                    LocalTime time = laterOf(state.cursor, state.eveningTime != null ? state.eveningTime : EVENING_FLOOR);
                    cafe.setSuggestedTime(time.format(TIME_FORMAT));
                    cafe.setOneLiner(withSlotNote(cafe.getOneLiner(), "분위기 좋은 카페로 추천 (선택)"));
                    state.items.add(cafe);
                    state.used.add(cafe.getContentId());
                    return state;
                })
                .defaultIfEmpty(state);
    }

    /** 4단계 파이프라인을 재사용해 origin에서 가장 가까운 후보 1건을 고른다. 없으면 빈 Mono(Mono는 null을 못 담음) */
    private Mono<RecommendationCandidate> fetchNearest(Itinerary itinerary, RecommendThemeTag theme,
            String originContentId, Integer originContentTypeId, Set<String> exclude) {
        RecommendationRequest req = RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .tags(List.of(theme.getTag()))
                .excludeContentIds(List.copyOf(exclude))
                .originContentId(originContentId)
                .originContentTypeId(originContentTypeId)
                .build();
        return recommendationPipeline.recommend(req)
                .flatMap(list -> list.stream()
                        .filter(c -> c.getContentId() != null && !exclude.contains(c.getContentId()))
                        .sorted((a, b) -> Double.compare(safeDistance(a), safeDistance(b)))
                        .findFirst()
                        .map(Mono::just)
                        .orElseGet(Mono::empty))
                .onErrorResume(e -> Mono.empty());
    }

    private double safeDistance(RecommendationCandidate c) {
        return c.getDistanceKm() == null ? Double.MAX_VALUE : c.getDistanceKm();
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

    /** 채우는 동안 누적되는 상태 - 단일 구독(Mono 체인) 안에서만 순차 변형되므로 가변 상태로 둬도 안전 */
    private static class PlanState {
        final List<RecommendationCandidate> items = new ArrayList<>();
        final Set<String> used = new HashSet<>();
        String anchorContentId;
        Integer anchorContentTypeId;
        String lastContentId;
        Integer lastContentTypeId;
        LocalTime cursor;
        LocalTime eveningTime;
    }
}
