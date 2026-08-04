package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.RegionCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 여행 기간(미래 날짜 포함)과 겹치는 지역 축제/행사를 찾아 "이 날짜에 이 축제 어때요?" 제안을 만든다.
 * TriggerDetectionService의 3종 트리거(기상/혼잡/영업)와 달리 "문제 감지"가 아니라 "기회 제안"이라
 * TriggerLevel(정상/주의/긴급) 산정에는 관여하지 않고 별도 목록으로만 얹힌다.
 */
@Slf4j
@Service
public class FestivalTriggerService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_SUGGESTIONS = 3;

    private final KorServiceClient korServiceClient;

    public FestivalTriggerService(KorServiceClient korServiceClient) {
        this.korServiceClient = korServiceClient;
    }

    public Mono<List<FestivalSuggestion>> findDuringTrip(RegionCode region, LocalDate tripStart, LocalDate tripEnd) {
        if (tripStart == null || tripEnd == null) {
            return Mono.just(List.of());
        }
        // searchFestival2는 eventStartDate 이후 "종료"되는 행사까지 폭넓게 주므로, 여행 시작일 기준으로 조회 후
        // 실제 여행 기간(tripStart~tripEnd)과 겹치는 것만 다시 걸러낸다.
        String queryFrom = tripStart.format(YYYYMMDD);
        return korServiceClient.searchFestival(queryFrom, region.getLDongRegnCd(), region.getLDongSignguCd(), 50, 1)
                .map(items -> filterAndMap(items, tripStart, tripEnd))
                .doOnNext(list -> log.info("[Festival] 여행기간({}~{}) 겹치는 축제 {}건", tripStart, tripEnd, list.size()));
    }

    private List<FestivalSuggestion> filterAndMap(List<JsonNode> items, LocalDate tripStart, LocalDate tripEnd) {
        List<FestivalSuggestion> result = new ArrayList<>();
        for (JsonNode item : items) {
            LocalDate eventStart = parseDate(item.path("eventstartdate").asText(null));
            LocalDate eventEnd = parseDate(item.path("eventenddate").asText(null));
            if (eventStart == null || eventEnd == null) {
                continue;
            }
            boolean overlaps = !tripStart.isAfter(eventEnd) && !eventStart.isAfter(tripEnd);
            if (!overlaps) {
                continue;
            }
            String contentId = item.path("contentid").asText(null);
            String title = item.path("title").asText(null);
            if (contentId == null || title == null) {
                continue;
            }
            String typeId = item.path("contenttypeid").asText(null);
            String thumbnail = item.path("firstimage").asText(null);
            result.add(FestivalSuggestion.builder()
                    .contentId(contentId)
                    .contentTypeId(typeId == null || typeId.isBlank() ? null : Integer.valueOf(typeId))
                    .placeName(title)
                    .thumbnailUrl(thumbnail == null || thumbnail.isBlank() ? null : thumbnail)
                    .addr1(item.path("addr1").asText(null))
                    .eventStartDate(item.path("eventstartdate").asText(null))
                    .eventEndDate(item.path("eventenddate").asText(null))
                    .build());
            if (result.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return result;
    }

    private LocalDate parseDate(String yyyyMMdd) {
        if (yyyyMMdd == null || yyyyMMdd.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(yyyyMMdd, YYYYMMDD);
        } catch (Exception e) {
            return null;
        }
    }
}
