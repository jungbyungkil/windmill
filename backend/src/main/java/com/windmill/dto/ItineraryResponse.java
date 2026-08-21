package com.windmill.dto;

import com.windmill.domain.AgeGroup;
import com.windmill.domain.CompanionType;
import com.windmill.domain.Itinerary;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Builder
public class ItineraryResponse {
    private Long itineraryId;
    private String signguFullCode;
    private String regionDisplayName;
    private String weatherNx;
    private String weatherNy;
    private LocalDate startDate;
    private LocalDate endDate;
    private CompanionType companionType;
    private boolean withPet;
    private boolean strollerFriendly;
    private boolean accessibleFriendly;
    private AgeGroup adultAgeGroup;
    private List<Integer> childAges;
    /** 전체 인원수 - 프론트 예상 총액(Σ) 계산에 쓰임(1인 요금 × partySize) */
    private int partySize;
    private List<ItineraryItemResponse> items;
    /** 확정된 날짜 목록 - 프론트 일자별 페이지 탭에서 체크표시/다음날 이동 가능 여부 판단에 사용 */
    private Set<LocalDate> confirmedDates;
    /** ACTIVE | ENDED - 종료된 일정은 프론트에서 수정 액션을 막는 데 쓰인다 */
    private ItineraryStatus status;

    /** 동선 최적화 직후 안내 문구(선택) */
    private String routeHint;
    /** 동선 최적화 직후 총 이동거리 km(선택, Haversine) */
    private Double optimizedDistanceKm;
    /** 삭제 직후 자동 대체된 장소명(선택) - 프론트가 "OO로 자동 채워드렸어요" 안내에 사용 */
    private String autoReplacedPlaceName;

    public static ItineraryResponse from(Itinerary itinerary, ItineraryStatus status) {
        ItineraryResponse response = from(itinerary);
        response.setStatus(status);
        return response;
    }

    public static ItineraryResponse from(Itinerary itinerary) {
        return ItineraryResponse.builder()
                .itineraryId(itinerary.getId())
                .signguFullCode(itinerary.getSignguFullCode())
                .regionDisplayName(itinerary.getRegionDisplayName())
                .weatherNx(itinerary.getWeatherNx())
                .weatherNy(itinerary.getWeatherNy())
                .startDate(itinerary.getStartDate())
                .endDate(itinerary.getEndDate())
                .companionType(itinerary.getCompanionType())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
                .partySize(itinerary.getPartySize())
                .items(itinerary.getItems().stream()
                        .sorted(java.util.Comparator
                                .comparing((com.windmill.domain.ItineraryItem i) -> {
                                    String t = i.getScheduledTime();
                                    if (t == null || t.isBlank()) return Integer.MAX_VALUE;
                                    String[] p = t.trim().split(":");
                                    if (p.length < 2) return Integer.MAX_VALUE;
                                    try {
                                        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
                                    } catch (NumberFormatException e) {
                                        return Integer.MAX_VALUE;
                                    }
                                })
                                .thenComparingInt(com.windmill.domain.ItineraryItem::getDisplayOrder))
                        .map(ItineraryItemResponse::from)
                        .collect(Collectors.toList()))
                .confirmedDates(itinerary.getConfirmedDates())
                .build();
    }
}
