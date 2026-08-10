package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.RegionCode;
import com.windmill.util.SimpleTtlCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 축제 정보는 정적 데이터에 가까워 짧은 트리거 폴링(1~5분) 주기마다 재조회할 필요가 없다 - 1,000 call/일 한도 보호 */
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Pattern HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final KorServiceClient korServiceClient;
    private final SimpleTtlCache<String, List<FestivalSuggestion>> cache = new SimpleTtlCache<>(CACHE_TTL);

    public FestivalTriggerService(KorServiceClient korServiceClient) {
        this.korServiceClient = korServiceClient;
    }

    public Mono<List<FestivalSuggestion>> findDuringTrip(RegionCode region, LocalDate tripStart, LocalDate tripEnd) {
        if (tripStart == null || tripEnd == null) {
            return Mono.just(List.of());
        }
        String cacheKey = region.getSignguFullCode() + ":" + tripStart.format(YYYYMMDD) + ":"
                + tripEnd.format(YYYYMMDD) + ":hp1";
        List<FestivalSuggestion> cached = cache.get(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        // searchFestival2는 eventStartDate 이후 "종료"되는 행사까지 폭넓게 주므로, 여행 시작일 기준으로 조회 후
        // 실제 여행 기간(tripStart~tripEnd)과 겹치는 것만 다시 걸러낸다.
        String queryFrom = tripStart.format(YYYYMMDD);
        return korServiceClient.searchFestival(queryFrom, region.getLDongRegnCd(), region.getLDongSignguCd(), 50, 1)
                .map(items -> filterAndMap(items, tripStart, tripEnd))
                .flatMap(this::enrichHomepages)
                .doOnNext(list -> {
                    cache.put(cacheKey, list);
                    log.info("[Festival] 여행기간({}~{}) 겹치는 축제 {}건", tripStart, tripEnd, list.size());
                });
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
            // searchFestival2에 homepage가 있으면 우선 사용
            String homepageUrl = extractHomepageUrl(item.path("homepage").asText(null));
            result.add(FestivalSuggestion.builder()
                    .contentId(contentId)
                    .contentTypeId(typeId == null || typeId.isBlank() ? null : Integer.valueOf(typeId))
                    .placeName(title)
                    .thumbnailUrl(thumbnail == null || thumbnail.isBlank() ? null : thumbnail)
                    .addr1(item.path("addr1").asText(null))
                    .eventStartDate(item.path("eventstartdate").asText(null))
                    .eventEndDate(item.path("eventenddate").asText(null))
                    .homepageUrl(homepageUrl)
                    .build());
            if (result.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return result;
    }

    /** detailCommon2로 홈페이지 URL 보강 (최대 3건) */
    private Mono<List<FestivalSuggestion>> enrichHomepages(List<FestivalSuggestion> list) {
        if (list == null || list.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(list)
                .concatMap(this::fillHomepageIfMissing)
                .collectList();
    }

    private Mono<FestivalSuggestion> fillHomepageIfMissing(FestivalSuggestion festival) {
        if (festival.getHomepageUrl() != null && !festival.getHomepageUrl().isBlank()) {
            return Mono.just(festival);
        }
        return korServiceClient.detailCommon(festival.getContentId())
                .map(common -> {
                    String url = extractHomepageUrl(common.path("homepage").asText(null));
                    if (url != null) {
                        festival.setHomepageUrl(url);
                    }
                    return festival;
                })
                .defaultIfEmpty(festival)
                .onErrorReturn(festival);
    }

    /** TourAPI homepage는 HTML 앵커이거나 순수 URL일 수 있다 */
    static String extractHomepageUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        Matcher href = HREF.matcher(text);
        if (href.find()) {
            return normalizeUrl(href.group(1).trim());
        }
        // HTML 태그 제거 후 http 추출
        String plain = text.replaceAll("<[^>]+>", " ").trim();
        int http = plain.toLowerCase().indexOf("http");
        if (http >= 0) {
            String rest = plain.substring(http).split("[\\s\"'<>]+")[0];
            return normalizeUrl(rest);
        }
        return null;
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (u.contains(".") && !u.contains(" ")) {
            return "https://" + u;
        }
        return null;
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
