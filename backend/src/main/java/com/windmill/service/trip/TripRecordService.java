package com.windmill.service.trip;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import com.windmill.domain.VisitRating;
import com.windmill.dto.CreateTripRecordRequest;
import com.windmill.dto.TripStoryFeedResponse;
import com.windmill.dto.VisitFeedbackRequest;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 여행 종료 후 1회성 회고 저장. "저장" 버튼으로 명시적 제출 (자동저장 아님).
 */
@Service
@RequiredArgsConstructor
public class TripRecordService {

    private final TripRecordRepository tripRecordRepository;
    private final ItineraryRepository itineraryRepository;

    @Transactional
    public TripRecord create(String sessionUuid, CreateTripRecordRequest request) {
        Itinerary itinerary = request.getItineraryId() == null
                ? null
                : itineraryRepository.findById(request.getItineraryId())
                        .orElseThrow(() -> new EntityNotFoundException("일정을 찾을 수 없습니다: " + request.getItineraryId()));

        TripRecord record = TripRecord.builder()
                .sessionUuid(sessionUuid)
                .itinerary(itinerary)
                .overallNote(request.getOverallNote())
                .overallRating(request.getOverallRating())
                .rerouteCount(request.getRerouteCount())
                .build();

        List<VisitFeedback> feedback = toFeedbackEntities(request.getVisitFeedback(), record, itinerary);
        record.setVisitFeedback(feedback);

        return tripRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public List<TripRecord> findBySession(String sessionUuid) {
        return tripRecordRepository.findBySessionUuid(sessionUuid);
    }

    /** 이 지역으로 떠나는 다른 여행자에게 보여줄 "엄지척(GOOD)" 최근 여행 기록 상위 5건 */
    @Transactional(readOnly = true)
    public List<TripRecord> findRecentGoodTripsByRegion(String signguFullCode) {
        return tripRecordRepository.findTop5ByItinerary_SignguFullCodeAndOverallRatingOrderByCompletedAtDesc(
                signguFullCode, VisitRating.GOOD);
    }

    /**
     * 첫 화면 인기 여행 기록 피드.
     * 트랜잭션 안에서 DTO로 변환해 Itinerary LAZY 로딩을 안전하게 처리한다.
     */
    @Transactional(readOnly = true)
    public List<TripStoryFeedResponse> findPopularStories() {
        return tripRecordRepository.findTop5ByOrderByLikeCountDescClickCountDescCompletedAtDesc().stream()
                .map(TripStoryFeedResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public TripStoryFeedResponse incrementLike(Long id) {
        TripRecord record = tripRecordRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("여행 기록을 찾을 수 없습니다: " + id));
        record.setLikeCount(record.getLikeCount() + 1);
        return TripStoryFeedResponse.from(record);
    }

    @Transactional
    public TripStoryFeedResponse incrementClick(Long id) {
        TripRecord record = tripRecordRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("여행 기록을 찾을 수 없습니다: " + id));
        record.setClickCount(record.getClickCount() + 1);
        return TripStoryFeedResponse.from(record);
    }

    /** 이 세션이 "별로"로 평가한 장소명 - 추천 파이프라인에서 제외 힌트로 사용 (기획안: 기록 → 추천 정확도 개선) */
    @Transactional(readOnly = true)
    public Set<String> getBadPlaceNames(String sessionUuid) {
        return tripRecordRepository.findBySessionUuid(sessionUuid).stream()
                .flatMap(record -> record.getVisitFeedback().stream())
                .filter(fb -> fb.getRating() == VisitRating.BAD)
                .map(VisitFeedback::getPlaceName)
                .collect(Collectors.toSet());
    }

    /**
     * itemId로 원본 ItineraryItem을 찾아 dayNo/timeSlot/category/contentId/isAlternate를 스냅샷한다 -
     * CommunityScheduleService의 지역별 집계가 나중에 조회하는 필드라 프론트에는 요구하지 않는다.
     * itinerary가 없거나(itineraryId 미지정) 항목이 이미 삭제됐으면 스냅샷 필드는 기본값(dayNo=1, timeSlot=오전)으로 남는다.
     */
    private List<VisitFeedback> toFeedbackEntities(List<VisitFeedbackRequest> requests, TripRecord record, Itinerary itinerary) {
        if (requests == null) {
            return List.of();
        }
        Map<Long, ItineraryItem> itemsById = itinerary == null
                ? Map.of()
                : itinerary.getItems().stream().collect(Collectors.toMap(ItineraryItem::getId, i -> i));

        return requests.stream().map(r -> {
            ItineraryItem item = itemsById.get(r.getItemId());
            VisitFeedback.VisitFeedbackBuilder builder = VisitFeedback.builder()
                    .tripRecord(record)
                    .itemId(r.getItemId())
                    .placeName(r.getPlaceName())
                    .rating(r.getRating())
                    .memo(r.getMemo());
            if (item != null) {
                builder.contentId(item.getContentId())
                        .contentTypeId(item.getContentTypeId())
                        .category(item.getCategory())
                        .isAlternate(item.isAlternate())
                        .dayNo(dayNo(itinerary, item))
                        .timeSlot(timeSlot(item.getScheduledTime()));
            } else {
                builder.dayNo(1).timeSlot(TIME_SLOT_MORNING);
            }
            return builder.build();
        }).collect(Collectors.toList());
    }

    private int dayNo(Itinerary itinerary, ItineraryItem item) {
        if (itinerary.getStartDate() == null || item.getVisitDate() == null) {
            return 1;
        }
        long diff = item.getVisitDate().toEpochDay() - itinerary.getStartDate().toEpochDay() + 1;
        return (int) Math.max(1, diff);
    }

    private static final String TIME_SLOT_MORNING = "오전";
    private static final String TIME_SLOT_LUNCH = "점심";
    private static final String TIME_SLOT_AFTERNOON = "오후";
    private static final String TIME_SLOT_EVENING = "저녁";

    /** "HH:mm" 시각을 4구간으로 버킷팅 - 오전(~10:59)/점심(11~13:59)/오후(14~17:59)/저녁(18~23:59), 파싱 실패 시 오전 */
    private String timeSlot(String scheduledTime) {
        if (scheduledTime == null || scheduledTime.isBlank()) {
            return TIME_SLOT_MORNING;
        }
        try {
            LocalTime time = LocalTime.parse(scheduledTime);
            int hour = time.getHour();
            if (hour < 11) {
                return TIME_SLOT_MORNING;
            } else if (hour < 14) {
                return TIME_SLOT_LUNCH;
            } else if (hour < 18) {
                return TIME_SLOT_AFTERNOON;
            }
            return TIME_SLOT_EVENING;
        } catch (Exception e) {
            return TIME_SLOT_MORNING;
        }
    }
}
