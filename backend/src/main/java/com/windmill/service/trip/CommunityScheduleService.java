package com.windmill.service.trip;

import com.windmill.domain.CompanionType;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import com.windmill.domain.VisitRating;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendedDayResponse;
import com.windmill.dto.RecommendedItemResponse;
import com.windmill.dto.RecommendedScheduleResponse;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.recommendation.Stage2BusinessHoursFilter;
import com.windmill.service.recommendation.Stage3CrowdRateFilter;
import com.windmill.service.recommendation.Stage4TagMatchingService;
import com.windmill.service.region.RegionCodeService;
import com.windmill.util.SimpleTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 같은 지역을 다녀간 여행자들의 기록(VisitFeedback)을 (dayNo, timeSlot)별로 집계해
 * "N명이 선택한 장소" 스케줄을 만든다. 후보 소스만 커뮤니티 집계로 바뀔 뿐, 검증은 기존
 * 4단계 파이프라인의 2~4단계(영업시간→집중률→태그매칭)를 그대로 재사용한다 - 1단계(연관관광지 조회)만
 * "다른 여행자가 실제로 다녀온 곳"으로 대체되는 셈이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityScheduleService {

    /** 이 미만이면 통계적으로 신뢰하기 어려워 빈 스케줄을 반환한다 - 컨트롤러/프론트가 기존 추천으로 폴백.
     *  브리프의 샘플 데이터가 정확히 5건이라 로컬 테스트가 바로 동작하도록 5로 둔다.
     *  운영 단계에서는 20~30 정도로 올리는 것을 권장(브리프 결정사항 #7). */
    private static final int COLD_START_THRESHOLD = 5;
    private static final int MAX_RECORDS = 200;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final List<String> SLOT_ORDER = List.of("오전", "점심", "오후", "저녁");

    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;
    private final Stage2BusinessHoursFilter stage2;
    private final Stage3CrowdRateFilter stage3;
    private final Stage4TagMatchingService stage4;
    private final SimpleTtlCache<String, RecommendedScheduleResponse> cache = new SimpleTtlCache<>(CACHE_TTL);

    public Mono<RecommendedScheduleResponse> recommend(String regionCode, CompanionType companionType, LocalDate startDate) {
        RegionCode region = regionCodeService.find(regionCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + regionCode));

        String cacheKey = regionCode + ":" + (companionType == null ? "ALL" : companionType.name());
        RecommendedScheduleResponse cached = cache.get(cacheKey);
        if (cached != null) {
            return Mono.just(withDates(cached, startDate));
        }

        List<TripRecord> records = companionType == null
                ? tripRecordRepository.findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc(regionCode)
                : tripRecordRepository.findTop200ByItinerary_SignguFullCodeAndItinerary_CompanionTypeOrderByCompletedAtDesc(
                        regionCode, companionType);
        if (records.size() > MAX_RECORDS) {
            records = records.subList(0, MAX_RECORDS);
        }

        String regionName = region.getSidoName() + " " + region.getSignguName();
        if (records.size() < COLD_START_THRESHOLD) {
            log.info("[Community] {} 기록 {}건 - 콜드스타트 임계값({}) 미만, 빈 스케줄 반환",
                    regionName, records.size(), COLD_START_THRESHOLD);
            RecommendedScheduleResponse coldStart = RecommendedScheduleResponse.builder()
                    .regionName(regionName).basedOnRecordCount(records.size()).days(List.of()).build();
            return Mono.just(coldStart);
        }

        List<VisitFeedback> allFeedback = records.stream()
                .flatMap(r -> r.getVisitFeedback().stream())
                .toList();

        Map<SlotKey, PlaceAccumulator> winnersBySlot = groupAndScore(allFeedback);
        List<SlotKey> orderedSlots = winnersBySlot.keySet().stream()
                .sorted(Comparator.comparingInt(SlotKey::dayNo).thenComparingInt(k -> SLOT_ORDER.indexOf(k.timeSlot())))
                .toList();

        List<RelatedCandidate> candidates = new ArrayList<>();
        Map<Integer, SlotMeta> metaByRank = new LinkedHashMap<>();
        int rank = 1;
        for (SlotKey key : orderedSlots) {
            PlaceAccumulator winner = winnersBySlot.get(key);
            candidates.add(RelatedCandidate.builder()
                    .placeName(winner.placeName)
                    .contentId(winner.contentId)
                    .contentTypeId(winner.contentTypeId)
                    .categoryLcls(winner.category)
                    .rank(rank)
                    .build());
            metaByRank.put(rank, new SlotMeta(key.dayNo(), key.timeSlot(), winner.count));
            rank++;
        }

        int recordCount = records.size();
        return stage2.filter(candidates)
                .flatMap(list -> stage3.filter(list, region))
                .flatMap(list -> stage4.match(list, null, null))
                .map(validated -> buildResponse(regionName, recordCount, validated, metaByRank))
                .doOnNext(response -> cache.put(cacheKey, response))
                .map(response -> withDates(response, startDate));
    }

    private RecommendedScheduleResponse buildResponse(String regionName, int recordCount,
                                                        List<RecommendationCandidate> validated,
                                                        Map<Integer, SlotMeta> metaByRank) {
        Map<Integer, List<RecommendedItemResponse>> itemsByDay = new LinkedHashMap<>();
        for (RecommendationCandidate candidate : validated) {
            SlotMeta meta = metaByRank.get(candidate.getRank());
            itemsByDay.computeIfAbsent(meta.dayNo, d -> new ArrayList<>())
                    .add(RecommendedItemResponse.builder()
                            .timeSlot(meta.timeSlot)
                            .selectedCount(meta.selectedCount)
                            .candidate(candidate)
                            .build());
        }

        List<RecommendedDayResponse> days = itemsByDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<RecommendedItemResponse> sortedItems = e.getValue().stream()
                            .sorted(Comparator.comparingInt(i -> SLOT_ORDER.indexOf(i.getTimeSlot())))
                            .toList();
                    return RecommendedDayResponse.builder().dayNo(e.getKey()).items(sortedItems).build();
                })
                .toList();

        return RecommendedScheduleResponse.builder()
                .regionName(regionName).basedOnRecordCount(recordCount).days(new ArrayList<>(days)).build();
    }

    /** startDate가 있으면 dayNo 기준 실제 날짜를 계산해 채운다(캐시된 응답을 복제해 반환 - 캐시 원본은 불변 유지) */
    private RecommendedScheduleResponse withDates(RecommendedScheduleResponse response, LocalDate startDate) {
        if (startDate == null || response.getDays().isEmpty()) {
            return response;
        }
        List<RecommendedDayResponse> withDate = response.getDays().stream()
                .map(d -> RecommendedDayResponse.builder()
                        .dayNo(d.getDayNo())
                        .date(startDate.plusDays(d.getDayNo() - 1L))
                        .items(d.getItems())
                        .build())
                .toList();
        return RecommendedScheduleResponse.builder()
                .regionName(response.getRegionName())
                .basedOnRecordCount(response.getBasedOnRecordCount())
                .days(new ArrayList<>(withDate))
                .build();
    }

    /** (dayNo,timeSlot)별 place 스코어링 - 등장 자체 +1, 피드백 가중치(GOOD=+1/NEUTRAL=0/BAD=-1) 추가, 슬롯당 최고점 1건만 남긴다 */
    private Map<SlotKey, PlaceAccumulator> groupAndScore(List<VisitFeedback> allFeedback) {
        Map<SlotKey, Map<String, PlaceAccumulator>> byPlace = new LinkedHashMap<>();
        for (VisitFeedback fb : allFeedback) {
            SlotKey key = new SlotKey(fb.getDayNo(), fb.getTimeSlot());
            Map<String, PlaceAccumulator> places = byPlace.computeIfAbsent(key, k -> new LinkedHashMap<>());
            PlaceAccumulator acc = places.computeIfAbsent(fb.getPlaceName(), PlaceAccumulator::new);
            acc.count++;
            acc.score += 1 + weight(fb.getRating());
            if (acc.contentId == null && fb.getContentId() != null) {
                acc.contentId = fb.getContentId();
                acc.contentTypeId = fb.getContentTypeId();
                acc.category = fb.getCategory();
            }
        }

        Map<SlotKey, PlaceAccumulator> winners = new LinkedHashMap<>();
        for (Map.Entry<SlotKey, Map<String, PlaceAccumulator>> e : byPlace.entrySet()) {
            PlaceAccumulator winner = e.getValue().values().stream()
                    .max(Comparator.comparingInt((PlaceAccumulator a) -> a.score)
                            .thenComparingInt(a -> a.count)
                            .thenComparing((PlaceAccumulator a) -> a.placeName, Comparator.reverseOrder()))
                    .orElseThrow();
            winners.put(e.getKey(), winner);
        }
        return winners;
    }

    private int weight(VisitRating rating) {
        return switch (rating) {
            case GOOD -> 1;
            case NEUTRAL -> 0;
            case BAD -> -1;
        };
    }

    private record SlotKey(int dayNo, String timeSlot) {
    }

    private record SlotMeta(int dayNo, String timeSlot, int selectedCount) {
    }

    private static class PlaceAccumulator {
        final String placeName;
        int count;
        int score;
        String contentId;
        Integer contentTypeId;
        String category;

        PlaceAccumulator(String placeName) {
            this.placeName = placeName;
        }
    }
}
