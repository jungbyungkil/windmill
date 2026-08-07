package com.windmill.service.trip;

import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import com.windmill.domain.VisitRating;
import com.windmill.dto.RegionCode;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 당일치기 추천 피드용 샘플 데이터(속초).
 * 기존 다일 시드를 지우고 하루짜리 기록만 넣는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevSeedService {

    private static final String SOKCHO_REGION_CODE = "51210";
    private static final int SEED_RECORD_COUNT = 5;

    private final ItineraryRepository itineraryRepository;
    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;

    private record ItemSpec(String timeSlot, String placeName, String contentId,
                             String category, VisitRating feedback, boolean isAlternate) {
    }

    private record TripSpec(String uuid, LocalDate date, CompanionType companionType,
                             boolean withPet, LocalDateTime completedAt, String note,
                             int likeCount, int clickCount, List<ItemSpec> items) {
    }

    /** 다일 기록 삭제 후, 당일치기 샘플이 부족하면 채운다 */
    @Transactional
    public ResetResult resetToDayTripFeed() {
        var multi = tripRecordRepository.findMultiDayOrInvalidRecords();
        int removed = multi.size();
        if (removed > 0) {
            tripRecordRepository.deleteAll(multi);
        }

        long dayTripCount = tripRecordRepository.findDayTripStories(PageRequest.of(0, 100)).size();
        boolean seeded = false;
        if (dayTripCount < SEED_RECORD_COUNT) {
            tripRecordRepository.deleteAll();
            seedDayTrips();
            seeded = true;
            dayTripCount = SEED_RECORD_COUNT;
        }
        log.info("[DevSeed] 다일 삭제 {}건, 당일치기 시드={}, 피드 후보 {}", removed, seeded, dayTripCount);
        return new ResetResult(removed, seeded, (int) dayTripCount);
    }

    @Transactional
    public boolean seedIfEmpty() {
        long existing = tripRecordRepository.findDayTripStories(PageRequest.of(0, SEED_RECORD_COUNT)).size();
        if (existing >= SEED_RECORD_COUNT) {
            log.info("[DevSeed] 당일치기 기록 {}건 이미 존재 - 시드 건너뜀", existing);
            return false;
        }
        seedDayTrips();
        return true;
    }

    private void seedDayTrips() {
        RegionCode region = regionCodeService.find(SOKCHO_REGION_CODE)
                .orElseThrow(() -> new IllegalStateException("속초 지역코드(51210)를 region-codes.json에서 찾을 수 없음"));
        String regionDisplayName = region.getSidoName() + " " + region.getSignguName();
        for (TripSpec spec : sampleDayTrips()) {
            seedOne(spec, regionDisplayName);
        }
        log.info("[DevSeed] 속초 당일치기 샘플 {}건 생성", sampleDayTrips().size());
    }

    private void seedOne(TripSpec spec, String regionDisplayName) {
        Itinerary itinerary = Itinerary.builder()
                .sessionUuid(spec.uuid())
                .signguFullCode(SOKCHO_REGION_CODE)
                .regionDisplayName(regionDisplayName)
                .startDate(spec.date())
                .endDate(spec.date())
                .companionType(spec.companionType())
                .withPet(spec.withPet())
                .build();

        int order = 0;
        for (ItemSpec item : spec.items()) {
            ItineraryItem entity = ItineraryItem.builder()
                    .itinerary(itinerary)
                    .contentId(item.contentId())
                    .placeName(item.placeName())
                    .scheduledTime(representativeTime(item.timeSlot()))
                    .visitDate(spec.date())
                    .category(item.category())
                    .isAlternate(item.isAlternate())
                    .displayOrder(order++)
                    .build();
            itinerary.getItems().add(entity);
        }
        itinerary = itineraryRepository.save(itinerary);

        int rerouteCount = (int) spec.items().stream().filter(ItemSpec::isAlternate).count();
        TripRecord record = TripRecord.builder()
                .sessionUuid(spec.uuid())
                .itinerary(itinerary)
                .overallRating(VisitRating.GOOD)
                .overallNote(spec.note())
                .rerouteCount(rerouteCount)
                .likeCount(spec.likeCount())
                .clickCount(spec.clickCount())
                .completedAt(spec.completedAt())
                .build();

        List<VisitFeedback> feedback = new ArrayList<>();
        List<ItineraryItem> savedItems = itinerary.getItems();
        for (int i = 0; i < spec.items().size(); i++) {
            ItemSpec itemSpec = spec.items().get(i);
            ItineraryItem savedItem = savedItems.get(i);
            feedback.add(VisitFeedback.builder()
                    .tripRecord(record)
                    .itemId(savedItem.getId())
                    .placeName(itemSpec.placeName())
                    .rating(itemSpec.feedback())
                    .contentId(itemSpec.contentId())
                    .category(itemSpec.category())
                    .dayNo(1)
                    .timeSlot(itemSpec.timeSlot())
                    .isAlternate(itemSpec.isAlternate())
                    .build());
        }
        record.setVisitFeedback(feedback);
        tripRecordRepository.save(record);
    }

    private String representativeTime(String timeSlot) {
        return switch (timeSlot) {
            case "오전" -> "09:00";
            case "점심" -> "12:30";
            case "오후" -> "15:00";
            case "저녁" -> "19:00";
            default -> "09:00";
        };
    }

    private List<TripSpec> sampleDayTrips() {
        return List.of(
                new TripSpec("uuid-daytrip-0001",
                        LocalDate.of(2026, 7, 12), CompanionType.SOLO, false,
                        LocalDateTime.of(2026, 7, 12, 20, 15),
                        "당일치기로 속초 바람 맞으며 힐링했어요. 해수욕장이 최고!",
                        12, 34,
                        List.of(
                                new ItemSpec("오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec("점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec("오후", "속초 아바이마을", "C0003", "관광지", VisitRating.NEUTRAL, false),
                                new ItemSpec("저녁", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD, false))),
                new TripSpec("uuid-daytrip-0002",
                        LocalDate.of(2026, 7, 19), CompanionType.COUPLE, false,
                        LocalDateTime.of(2026, 7, 19, 19, 40),
                        "연인과 당일치기, 동해가 예뻤어요.",
                        9, 21,
                        List.of(
                                new ItemSpec("오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec("점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec("오후", "설악산 케이블카", "C0004", "관광지", VisitRating.NEUTRAL, false),
                                new ItemSpec("저녁", "속초등대해수욕장", "C0006", "해변", VisitRating.GOOD, false))),
                new TripSpec("uuid-daytrip-0003",
                        LocalDate.of(2026, 7, 27), CompanionType.FAMILY_4, false,
                        LocalDateTime.of(2026, 7, 27, 21, 5),
                        "아이들과 당일치기. 비 소식에 바람개비가 실내로 잘 바꿔줬어요.",
                        18, 47,
                        List.of(
                                new ItemSpec("오전", "속초시립박물관", "C0008", "실내", VisitRating.GOOD, true),
                                new ItemSpec("점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec("오후", "외옹치 바다향기로", "C0007", "관광지", VisitRating.GOOD, true),
                                new ItemSpec("저녁", "대포항 회센터", "C0005", "맛집", VisitRating.NEUTRAL, false))),
                new TripSpec("uuid-daytrip-0004",
                        LocalDate.of(2026, 8, 2), CompanionType.SOLO, true,
                        LocalDateTime.of(2026, 8, 2, 18, 50),
                        "반려견이랑 당일치기로도 편하게 다녔어요.",
                        7, 15,
                        List.of(
                                new ItemSpec("오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec("오후", "속초 아바이마을", "C0003", "관광지", VisitRating.GOOD, false),
                                new ItemSpec("저녁", "속초등대해수욕장", "C0006", "해변", VisitRating.NEUTRAL, false))),
                new TripSpec("uuid-daytrip-0005",
                        LocalDate.of(2026, 8, 10), CompanionType.EXTENDED_FAMILY, false,
                        LocalDateTime.of(2026, 8, 10, 20, 30),
                        "대가족 당일치기인데도 식사·동선이 잘 맞았어요.",
                        5, 11,
                        List.of(
                                new ItemSpec("오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec("점심", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD, false),
                                new ItemSpec("오후", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD, false),
                                new ItemSpec("저녁", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false)))
        );
    }

    public record ResetResult(int removedMultiDay, boolean seeded, int dayTripCount) {
    }
}
