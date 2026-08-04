package com.windmill.service.trip;

import com.windmill.domain.Itinerary;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitFeedback;
import com.windmill.domain.VisitRating;
import com.windmill.dto.CreateTripRecordRequest;
import com.windmill.dto.VisitFeedbackRequest;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

        List<VisitFeedback> feedback = toFeedbackEntities(request.getVisitFeedback(), record);
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

    /** 이 세션이 "별로"로 평가한 장소명 - 추천 파이프라인에서 제외 힌트로 사용 (기획안: 기록 → 추천 정확도 개선) */
    @Transactional(readOnly = true)
    public Set<String> getBadPlaceNames(String sessionUuid) {
        return tripRecordRepository.findBySessionUuid(sessionUuid).stream()
                .flatMap(record -> record.getVisitFeedback().stream())
                .filter(fb -> fb.getRating() == VisitRating.BAD)
                .map(VisitFeedback::getPlaceName)
                .collect(Collectors.toSet());
    }

    private List<VisitFeedback> toFeedbackEntities(List<VisitFeedbackRequest> requests, TripRecord record) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(r -> VisitFeedback.builder()
                .tripRecord(record)
                .itemId(r.getItemId())
                .placeName(r.getPlaceName())
                .rating(r.getRating())
                .memo(r.getMemo())
                .build()).collect(Collectors.toList());
    }
}
