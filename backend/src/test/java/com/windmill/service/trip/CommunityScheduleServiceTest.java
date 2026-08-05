package com.windmill.service.trip;

import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import com.windmill.domain.VisitRating;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendedItemResponse;
import com.windmill.dto.RecommendedScheduleResponse;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.recommendation.Stage2BusinessHoursFilter;
import com.windmill.service.recommendation.Stage3CrowdRateFilter;
import com.windmill.service.recommendation.Stage4TagMatchingService;
import com.windmill.service.region.RegionCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 브리프 6번 항목의 샘플 데이터 5건과 동일한 VisitFeedback 조합으로 집계 로직(스코어링 + 슬롯별 argmax +
 * Stage2~4 재정렬 이후 rank 기반 슬롯메타 복원)을 검증한다. Stage2/3/4는 실제 외부 API를 타므로
 * pass-through/역순 mock으로 대체해 "우리 그룹핑 로직"만 순수하게 확인한다.
 */
class CommunityScheduleServiceTest {

    private TripRecordRepository tripRecordRepository;
    private RegionCodeService regionCodeService;
    private Stage2BusinessHoursFilter stage2;
    private Stage3CrowdRateFilter stage3;
    private Stage4TagMatchingService stage4;
    private CommunityScheduleService service;

    @BeforeEach
    void setUp() {
        tripRecordRepository = mock(TripRecordRepository.class);
        regionCodeService = mock(RegionCodeService.class);
        stage2 = mock(Stage2BusinessHoursFilter.class);
        stage3 = mock(Stage3CrowdRateFilter.class);
        stage4 = mock(Stage4TagMatchingService.class);
        service = new CommunityScheduleService(tripRecordRepository, regionCodeService, stage2, stage3, stage4);

        when(regionCodeService.find("51210")).thenReturn(Optional.of(RegionCode.builder()
                .sidoName("강원특별자치도").signguName("속초시").signguFullCode("51210")
                .lDongRegnCd("51").lDongSignguCd("210").build()));

        // Stage2: 검증 없이 그대로 통과 (오늘 이미 확인된 실제 계약과 동일)
        when(stage2.filter(anyList())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Stage3: 실제로는 crowdRate로 재정렬한다 - 순서가 바뀌어도 rank로 슬롯메타를 복원하는지 검증하려고 일부러 뒤집는다
        when(stage3.filter(anyList(), any())).thenAnswer(inv -> {
            List<RelatedCandidate> in = inv.getArgument(0);
            List<RelatedCandidate> reversed = new ArrayList<>(in);
            Collections.reverse(reversed);
            return Mono.just(reversed);
        });
        // Stage4: RelatedCandidate -> RecommendationCandidate 1:1 변환 (rank/placeName/category 보존)
        when(stage4.match(anyList(), any(), any())).thenAnswer(inv -> {
            List<RelatedCandidate> in = inv.getArgument(0);
            List<RecommendationCandidate> out = in.stream()
                    .map(c -> RecommendationCandidate.builder()
                            .placeName(c.getPlaceName())
                            .contentId(c.getContentId())
                            .category(c.getCategoryLcls())
                            .rank(c.getRank())
                            .build())
                    .toList();
            return Mono.just(out);
        });

        when(tripRecordRepository.findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc("51210"))
                .thenReturn(sampleRecords());
    }

    @Test
    void basedOnRecordCountMatchesSampleSize() {
        RecommendedScheduleResponse response = service.recommend("51210", null, null).block();
        assertEquals(5, response.getBasedOnRecordCount());
    }

    @Test
    void day1MorningWinnerIsBeachWithAllFiveVotes() {
        RecommendedScheduleResponse response = service.recommend("51210", null, null).block();
        RecommendedItemResponse morning = findItem(response, 1, "오전");
        assertEquals("속초해수욕장", morning.getCandidate().getPlaceName());
        assertEquals(5, morning.getSelectedCount());
    }

    @Test
    void day1LunchWinnerIsChickenAlleyWithThreeVotes() {
        RecommendedScheduleResponse response = service.recommend("51210", null, null).block();
        RecommendedItemResponse lunch = findItem(response, 1, "점심");
        assertEquals("중앙시장 닭강정 골목", lunch.getCandidate().getPlaceName());
        assertEquals(3, lunch.getSelectedCount());
    }

    private RecommendedItemResponse findItem(RecommendedScheduleResponse response, int dayNo, String timeSlot) {
        return response.getDays().stream()
                .filter(d -> d.getDayNo() == dayNo)
                .findFirst().orElseThrow()
                .getItems().stream()
                .filter(i -> i.getTimeSlot().equals(timeSlot))
                .findFirst().orElseThrow();
    }

    private VisitFeedback fb(int dayNo, String timeSlot, String placeName, String contentId,
                              String category, VisitRating rating) {
        return VisitFeedback.builder()
                .itemId(1L).placeName(placeName).rating(rating)
                .contentId(contentId).category(category).dayNo(dayNo).timeSlot(timeSlot)
                .build();
    }

    private List<TripRecord> sampleRecords() {
        TripRecord r1 = TripRecord.builder().visitFeedback(List.of(
                fb(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD),
                fb(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD),
                fb(1, "오후", "속초 아바이마을", "C0003", "관광지", VisitRating.NEUTRAL),
                fb(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD),
                fb(2, "저녁", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD)
        )).build();
        TripRecord r2 = TripRecord.builder().visitFeedback(List.of(
                fb(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD),
                fb(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD),
                fb(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.NEUTRAL),
                fb(2, "오후", "속초등대해수욕장", "C0006", "해변", VisitRating.GOOD)
        )).build();
        TripRecord r3 = TripRecord.builder().visitFeedback(List.of(
                fb(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD),
                fb(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD),
                fb(1, "오후", "외옹치 바다향기로", "C0007", "관광지", VisitRating.GOOD),
                fb(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD),
                fb(2, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.NEUTRAL)
        )).build();
        TripRecord r4 = TripRecord.builder().visitFeedback(List.of(
                fb(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD),
                fb(1, "오후", "속초 아바이마을", "C0003", "관광지", VisitRating.GOOD),
                fb(2, "오전", "속초등대해수욕장", "C0006", "해변", VisitRating.NEUTRAL)
        )).build();
        TripRecord r5 = TripRecord.builder().visitFeedback(List.of(
                fb(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD),
                fb(1, "점심", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD),
                fb(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD),
                fb(2, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD),
                fb(3, "오전", "속초등대해수욕장", "C0006", "해변", VisitRating.NEUTRAL)
        )).build();
        return List.of(r1, r2, r3, r4, r5);
    }
}
