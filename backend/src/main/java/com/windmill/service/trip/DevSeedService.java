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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 여행기록 기반 추천 기능 브리프의 샘플 데이터 5건(강원도 속초시)을 로컬/개발 환경에만 넣기 위한 시드.
 * 브리프의 원본 DDL은 trip_records에 region_code 등을 직접 저장하지만, 우리 스키마는 Itinerary를
 * 참조하므로 Itinerary+ItineraryItem을 먼저 만들고 그 위에 TripRecord+VisitFeedback을 얹는다.
 * @Profile("!prod")로 Render 배포(prod 프로필)에서는 빈 자체가 등록되지 않는다.
 */
@Slf4j
@Service
@Profile("!prod")
@RequiredArgsConstructor
public class DevSeedService {

    private static final String SOKCHO_REGION_CODE = "51210";
    private static final int SEED_RECORD_COUNT = 5;

    private final ItineraryRepository itineraryRepository;
    private final TripRecordRepository tripRecordRepository;
    private final RegionCodeService regionCodeService;

    private record ItemSpec(int dayNo, String timeSlot, String placeName, String contentId,
                             String category, VisitRating feedback, boolean isAlternate) {
    }

    private record TripSpec(String uuid, LocalDate start, LocalDate end, CompanionType companionType,
                             boolean withPet, LocalDateTime completedAt, List<ItemSpec> items) {
    }

    /** @return 실제로 시드했으면 true, 이미 충분한 기록이 있어 건너뛰었으면 false */
    @Transactional
    public boolean seedIfEmpty() {
        int existing = tripRecordRepository
                .findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc(SOKCHO_REGION_CODE).size();
        if (existing >= SEED_RECORD_COUNT) {
            log.info("[DevSeed] 속초 기록 {}건 이미 존재 - 시드 건너뜀", existing);
            return false;
        }

        RegionCode region = regionCodeService.find(SOKCHO_REGION_CODE)
                .orElseThrow(() -> new IllegalStateException("속초 지역코드(51210)를 region-codes.json에서 찾을 수 없음"));
        String regionDisplayName = region.getSidoName() + " " + region.getSignguName();

        for (TripSpec spec : sampleSpecs()) {
            seedOne(spec, regionDisplayName);
        }
        log.info("[DevSeed] 속초 샘플 여행기록 {}건 생성 완료", sampleSpecs().size());
        return true;
    }

    private void seedOne(TripSpec spec, String regionDisplayName) {
        Itinerary itinerary = Itinerary.builder()
                .sessionUuid(spec.uuid())
                .signguFullCode(SOKCHO_REGION_CODE)
                .regionDisplayName(regionDisplayName)
                .startDate(spec.start())
                .endDate(spec.end())
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
                    .visitDate(spec.start().plusDays(item.dayNo() - 1L))
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
                .rerouteCount(rerouteCount)
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
                    .dayNo(itemSpec.dayNo())
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

    /** 브리프 6번 항목의 SQL 샘플과 1:1 대응 */
    private List<TripSpec> sampleSpecs() {
        return List.of(
                new TripSpec("uuid-sokcho-0001",
                        LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12),
                        CompanionType.SOLO, false, LocalDateTime.of(2026, 7, 12, 20, 15),
                        List.of(
                                new ItemSpec(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec(1, "오후", "속초 아바이마을", "C0003", "관광지", VisitRating.NEUTRAL, false),
                                new ItemSpec(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD, false),
                                new ItemSpec(2, "저녁", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD, false))),
                new TripSpec("uuid-sokcho-0002",
                        LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19),
                        CompanionType.COUPLE, false, LocalDateTime.of(2026, 7, 19, 19, 40),
                        List.of(
                                new ItemSpec(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.NEUTRAL, false),
                                new ItemSpec(2, "오후", "속초등대해수욕장", "C0006", "해변", VisitRating.GOOD, false))),
                new TripSpec("uuid-sokcho-0003",
                        LocalDate.of(2026, 7, 25), LocalDate.of(2026, 7, 27),
                        CompanionType.FAMILY_4, false, LocalDateTime.of(2026, 7, 27, 21, 5),
                        List.of(
                                new ItemSpec(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec(1, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec(1, "오후", "외옹치 바다향기로", "C0007", "관광지", VisitRating.GOOD, true),
                                new ItemSpec(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD, false),
                                new ItemSpec(2, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.NEUTRAL, false))),
                new TripSpec("uuid-sokcho-0004",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2),
                        CompanionType.SOLO, true, LocalDateTime.of(2026, 8, 2, 18, 50),
                        List.of(
                                new ItemSpec(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec(1, "오후", "속초 아바이마을", "C0003", "관광지", VisitRating.GOOD, false),
                                new ItemSpec(2, "오전", "속초등대해수욕장", "C0006", "해변", VisitRating.NEUTRAL, false))),
                new TripSpec("uuid-sokcho-0005",
                        LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 10),
                        CompanionType.EXTENDED_FAMILY, false, LocalDateTime.of(2026, 8, 10, 20, 30),
                        List.of(
                                new ItemSpec(1, "오전", "속초해수욕장", "C0001", "해변", VisitRating.GOOD, false),
                                new ItemSpec(1, "점심", "대포항 회센터", "C0005", "맛집", VisitRating.GOOD, false),
                                new ItemSpec(2, "오전", "설악산 케이블카", "C0004", "관광지", VisitRating.GOOD, false),
                                new ItemSpec(2, "점심", "중앙시장 닭강정 골목", "C0002", "맛집", VisitRating.GOOD, false),
                                new ItemSpec(3, "오전", "속초등대해수욕장", "C0006", "해변", VisitRating.NEUTRAL, false)))
        );
    }
}
