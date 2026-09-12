package com.windmill.service.recommendation;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.repository.ItineraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 2박3일 등 여러 날을 당일치기 여러 건으로 나눠 쓰는 사용자를 위한 교차-일정 제외 목록 계산
 * (2026-09-12 사용자 요청: "당일치기 1"(9/12 속초시)·"당일치기 2"(9/13 속초시)가 서로 독립된
 * 일정으로 취급돼 같은 추천을 반복해서 받게 됨).
 *
 * 같은 세션(같은 기기·사용자)·같은 지역의 다른 당일치기에 이미 담긴 장소 contentId를 모아,
 * AI 초안/스마트 동선/앵커/대안 생성 시 excludeContentIds에 얹을 수 있게 한다. 날짜가 몇 달씩
 * 떨어진 "완전히 다른 재방문"까지 섞이지 않도록 시작일 기준 근접일(WINDOW_DAYS) 이내만 본다 -
 * 그 밖은 같은 지역이라도 다른 여행으로 보고 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class SiblingTripExclusionResolver {

    /** 2박3일은 물론 일주일 남짓한 여행을 당일치기로 쪼갠 경우까지 넉넉히 커버 */
    private static final long WINDOW_DAYS = 14;

    private final ItineraryRepository itineraryRepository;

    public Set<String> resolve(Itinerary itinerary) {
        if (itinerary == null || itinerary.getId() == null
                || itinerary.getSessionUuid() == null || itinerary.getSignguFullCode() == null) {
            return Set.of();
        }
        List<Itinerary> siblings = itineraryRepository.findBySessionUuidAndSignguFullCodeAndIdNot(
                itinerary.getSessionUuid(), itinerary.getSignguFullCode(), itinerary.getId());

        return siblings.stream()
                .filter(sibling -> withinWindow(itinerary, sibling))
                .flatMap(sibling -> sibling.getItems().stream())
                .map(ItineraryItem::getContentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
    }

    private boolean withinWindow(Itinerary itinerary, Itinerary sibling) {
        if (itinerary.getStartDate() == null || sibling.getStartDate() == null) return true;
        long diff = Math.abs(ChronoUnit.DAYS.between(itinerary.getStartDate(), sibling.getStartDate()));
        return diff <= WINDOW_DAYS;
    }
}
