package com.windmill.dto;

import com.windmill.domain.ItineraryItem;
import com.windmill.domain.TripRecord;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 첫 화면(대시보드) 여행 기록 피드 카드.
 * 좋아요·클릭 많은 순으로 최대 5건을 보여 흥미를 유도한다.
 */
@Data
@Builder
public class TripStoryFeedResponse {
    private Long id;
    private String signguFullCode;
    private String regionDisplayName;
    private String overallNote;
    private String overallRating;
    private List<String> placeNames;
    private String thumbnailUrl;
    private String startDate;
    private String endDate;
    private String companionType;
    private boolean withPet;
    private int likeCount;
    private int clickCount;
    /** 여행 중 변수(날씨·혼잡·동선 등)에 대응해 일정을 바꾼 횟수 */
    private int rerouteCount;
    /** 대안(대체)으로 담은 장소 수 - 다른 여행자 참고용 */
    private int alternatePlaceCount;
    private String completedAt;

    public static TripStoryFeedResponse from(TripRecord record) {
        List<ItineraryItem> items = record.getItinerary() == null
                ? List.of()
                : record.getItinerary().getItems();
        List<String> placeNames = items.stream()
                .map(ItineraryItem::getPlaceName)
                .collect(Collectors.toList());
        String thumbnailUrl = items.stream()
                .map(ItineraryItem::getThumbnailUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
        int alternateCount = (int) items.stream().filter(ItineraryItem::isAlternate).count();
        return TripStoryFeedResponse.builder()
                .id(record.getId())
                .signguFullCode(record.getItinerary() == null ? null : record.getItinerary().getSignguFullCode())
                .regionDisplayName(record.getItinerary() == null ? null : record.getItinerary().getRegionDisplayName())
                .overallNote(record.getOverallNote())
                .overallRating(record.getOverallRating() == null ? null : record.getOverallRating().name())
                .placeNames(placeNames)
                .thumbnailUrl(thumbnailUrl)
                .startDate(record.getItinerary() == null || record.getItinerary().getStartDate() == null
                        ? null : record.getItinerary().getStartDate().toString())
                .endDate(record.getItinerary() == null || record.getItinerary().getEndDate() == null
                        ? null : record.getItinerary().getEndDate().toString())
                .companionType(record.getItinerary() == null || record.getItinerary().getCompanionType() == null
                        ? null : record.getItinerary().getCompanionType().name())
                .withPet(record.getItinerary() != null && record.getItinerary().isWithPet())
                .likeCount(record.getLikeCount())
                .clickCount(record.getClickCount())
                .rerouteCount(record.getRerouteCount())
                .alternatePlaceCount(alternateCount)
                .completedAt(record.getCompletedAt() == null ? null : record.getCompletedAt().toString())
                .build();
    }
}
