package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.dto.PlaceHoursCheckRequest;
import com.windmill.dto.PlaceHoursCheckResponse;
import com.windmill.dto.PlaceHoursWarning;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.repository.ItineraryRepository;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.service.recommendation.BusinessHoursEvaluator.ClosedDayKind;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.ClosingTimeGate;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 일정 추가/편집에서 휴무·마감 여부를 알려 주기만 한다. 저장을 막거나 시각을 자동 조정하지 않는다.
 * (스마트 동선 단계는 기존 ClosingTimeGate가 최적 경로를 계산한다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceHoursCheckService {

    private final ItineraryRepository itineraryRepository;
    private final TourAttractionService tourAttractionService;

    @Transactional(readOnly = true)
    public PlaceHoursCheckResponse check(Long itineraryId, PlaceHoursCheckRequest request) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new EntityNotFoundException("일정을 찾을 수 없습니다: " + itineraryId));
        LocalDate visitDate = request.getVisitDate() != null ? request.getVisitDate() : itinerary.getStartDate();
        String placeName = blankToNull(request.getPlaceName());
        if (placeName == null) {
            placeName = "이 장소";
        }

        String restDateText = blankToNull(request.getRestDateText());
        String closeTime = blankToNull(request.getCloseTime());
        String useTimeText = blankToNull(request.getUseTimeText());
        String scheduledTime = blankToNull(request.getScheduledTime());
        if (closeTime == null && useTimeText != null) {
            closeTime = BusinessHoursEvaluator.formatHhMm(
                    BusinessHoursEvaluator.extractCloseTimeFromText(useTimeText));
        }

        Map<String, String> intro = resolveIntro(request, restDateText, closeTime, scheduledTime);
        if (restDateText == null) {
            restDateText = BusinessHoursEvaluator.extractRestDateText(intro);
        }
        if (closeTime == null) {
            closeTime = BusinessHoursEvaluator.formatHhMm(BusinessHoursEvaluator.extractCloseTime(intro));
        }
        if (useTimeText == null) {
            useTimeText = BusinessHoursEvaluator.extractUseTimeText(intro);
        }

        List<PlaceHoursWarning> warnings = new ArrayList<>();
        ClosedDayKind closedKind = BusinessHoursEvaluator.classifyClosedDay(restDateText, visitDate);
        if (closedKind == ClosedDayKind.REGULAR) {
            String weekday = BusinessHoursEvaluator.weekdayKorean(visitDate);
            warnings.add(PlaceHoursWarning.builder()
                    .code("REST_DAY")
                    .message(placeName + "은 " + weekday + "요일 휴무입니다.")
                    .detail(restDateText)
                    .build());
        } else if (closedKind == ClosedDayKind.HOLIDAY_SHIFT) {
            warnings.add(PlaceHoursWarning.builder()
                    .code("HOLIDAY_SHIFT")
                    .message(placeName + "은 공휴일이라 휴무가 밀려, 방문일에 휴관입니다.")
                    .detail(restDateText)
                    .build());
        }

        // 휴무가 아니면, 도착 시각을 알 때만 마감 임박을 경고한다(모르면 생략).
        if (closedKind == ClosedDayKind.NONE && scheduledTime != null) {
            LocalTime arrival = ClosingTimeGate.parseHhMm(scheduledTime);
            LocalTime close = ClosingTimeGate.parseHhMm(closeTime);
            int buffer = close != null ? BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES : 0;
            ClosingTimeGate.CheckResult closing = ClosingTimeGate.check(close, arrival, buffer);
            if (closing.blocked()) {
                String closeLabel = ClosingTimeGate.formatFriendly(close);
                warnings.add(PlaceHoursWarning.builder()
                        .code("CLOSING_SOON")
                        .message(placeName + "은 " + closeLabel + "에 마감돼요. 도착이 마감에 가까워요.")
                        .detail(useTimeText)
                        .build());
            }
        }

        return PlaceHoursCheckResponse.builder()
                .warning(!warnings.isEmpty())
                .warnings(warnings)
                .restDateText(restDateText)
                .closeTime(closeTime)
                .useTimeText(useTimeText)
                .build();
    }

    /**
     * restDateText가 이미 있으면 휴무 판정에 추가 조회가 필요 없다.
     * 도착 시각이 있는데 마감 시각이 없을 때만(또는 휴무 원문이 없을 때만) 상세를 본다.
     */
    private Map<String, String> resolveIntro(PlaceHoursCheckRequest request, String restDateText,
                                             String closeTime, String scheduledTime) {
        String contentId = blankToNull(request.getContentId());
        Integer typeId = request.getContentTypeId();
        if (contentId == null || typeId == null) {
            return Map.of();
        }
        boolean needRest = restDateText == null;
        boolean needClose = scheduledTime != null && closeTime == null;
        if (!needRest && !needClose) {
            return Map.of();
        }
        TourAttractionDetail cached = tourAttractionService.peekCachedDetail(contentId);
        if (cached != null && cached.getIntroFields() != null && !cached.getIntroFields().isEmpty()) {
            return cached.getIntroFields();
        }
        try {
            TourAttractionDetail detail = tourAttractionService.getDetail(contentId, typeId)
                    .block(Duration.ofSeconds(8));
            if (detail != null && detail.getIntroFields() != null) {
                return detail.getIntroFields();
            }
        } catch (Exception e) {
            log.warn("[hours-check] 상세조회 실패 contentId={} - 경고 없이 통과", contentId, e);
        }
        return Map.of();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
