package com.windmill.dto;

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
    private List<ItineraryItemResponse> items;
    /** 확정된 날짜 목록 - 프론트 일자별 페이지 탭에서 체크표시/다음날 이동 가능 여부 판단에 사용 */
    private Set<LocalDate> confirmedDates;

    /** 동선 최적화 직후 안내 문구(선택) */
    private String routeHint;
    /** 동선 최적화 직후 총 이동거리 km(선택, Haversine) */
    private Double optimizedDistanceKm;

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
