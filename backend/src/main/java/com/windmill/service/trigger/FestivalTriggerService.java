package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.windmill.client.KorServiceClient;
import com.windmill.client.TourApiAreaCodes;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.RegionCode;
import com.windmill.util.HomepageUrlExtractor;
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
    /** 축제/공연/행사 */
    static final int FESTIVAL_CONTENT_TYPE_ID = 15;
    /** 축제 정보는 정적 데이터에 가까워 짧은 트리거 폴링(1~5분) 주기마다 재조회할 필요가 없다 - 1,000 call/일 한도 보호 */
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final KorServiceClient korServiceClient;
    private final SimpleTtlCache<String, List<FestivalSuggestion>> cache = new SimpleTtlCache<>(CACHE_TTL);

    public FestivalTriggerService(KorServiceClient korServiceClient) {
        this.korServiceClient = korServiceClient;
    }

    public Mono<List<FestivalSuggestion>> findDuringTrip(RegionCode region, LocalDate tripStart, LocalDate tripEnd) {
        if (region == null || region.getLDongRegnCd() == null || region.getLDongRegnCd().isBlank()
                || tripStart == null || tripEnd == null) {
            return Mono.just(List.of());
        }
        String regn = region.getLDongRegnCd();
        String cacheKey = regn + ":" + tripStart.format(YYYYMMDD) + ":" + tripEnd.format(YYYYMMDD) + ":sido";
        List<FestivalSuggestion> cached = cache.get(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        String queryFrom = tripStart.format(YYYYMMDD);
        String areaCode = TourApiAreaCodes.fromLDongRegnCd(regn);
        return searchByAreaThenPages(queryFrom, areaCode, region, tripStart, tripEnd)
                .flatMap(list -> {
                    if (!list.isEmpty()) {
                        return Mono.just(list);
                    }
                    log.info("[Festival] searchFestival2 0건 - areaBasedList2(축제)로 시·도={} 재조회", regn);
                    return fromAreaBasedList(region, tripStart, tripEnd);
                })
                .flatMap(this::enrichHomepages)
                .doOnNext(list -> {
                    if (!list.isEmpty()) {
                        cache.put(cacheKey, list);
                    }
                    log.info("[Festival] 여행기간({}~{}) 시·도={} areaCode={} 겹치는 축제 {}건",
                            tripStart, tripEnd, regn, areaCode, list.size());
                });
    }

    private Mono<List<FestivalSuggestion>> searchByAreaThenPages(String queryFrom, String areaCode,
                                                                 RegionCode region, LocalDate tripStart,
                                                                 LocalDate tripEnd) {
        return korServiceClient.searchFestival(queryFrom, areaCode, 100, 1)
                .map(items -> filterAndMap(items, region, tripStart, tripEnd))
                .flatMap(page1 -> {
                    if (!page1.isEmpty()) {
                        return Mono.just(page1);
                    }
                    return korServiceClient.searchFestival(queryFrom, areaCode, 100, 2)
                            .map(items -> filterAndMap(items, region, tripStart, tripEnd));
                });
    }

    /**
     * areaBasedList2 축제 목록에는 기간 필드가 빠지는 경우가 많다.
     * 없으면 소개정보(detailIntro2)의 eventstartdate/enddate로 보강한 뒤 여행일과 겹치는 것만 남긴다.
     */
    private Mono<List<FestivalSuggestion>> fromAreaBasedList(RegionCode region, LocalDate tripStart,
                                                             LocalDate tripEnd) {
        return korServiceClient.areaBasedList(FESTIVAL_CONTENT_TYPE_ID, region.getLDongRegnCd(), null, 40, 1, "C")
                .flatMapMany(Flux::fromIterable)
                .filter(item -> matchesRegion(item, region))
                .concatMap(this::withEventDates)
                .map(item -> filterAndMap(List.of(item), region, tripStart, tripEnd))
                .filter(list -> !list.isEmpty())
                .take(MAX_SUGGESTIONS)
                .concatMap(Flux::fromIterable)
                .collectList();
    }

    private Mono<JsonNode> withEventDates(JsonNode item) {
        if (parseDate(text(item, "eventstartdate", "eventStartDate")) != null
                && parseDate(text(item, "eventenddate", "eventEndDate")) != null) {
            return Mono.just(item);
        }
        String contentId = text(item, "contentid", "contentId");
        if (contentId == null) {
            return Mono.just(item);
        }
        return korServiceClient.detailIntro(contentId, FESTIVAL_CONTENT_TYPE_ID)
                .map(intro -> copyEventDates(item, intro))
                .defaultIfEmpty(item)
                .onErrorReturn(item);
    }

    static JsonNode copyEventDates(JsonNode item, JsonNode intro) {
        if (item == null || intro == null || !item.isObject()) {
            return item;
        }
        String start = text(intro, "eventstartdate", "eventStartDate");
        String end = text(intro, "eventenddate", "eventEndDate");
        ObjectNode copy = item.deepCopy();
        if (start != null) {
            copy.put("eventstartdate", start);
        }
        if (end != null) {
            copy.put("eventenddate", end);
        }
        return copy;
    }

    static List<FestivalSuggestion> filterAndMap(List<JsonNode> items, RegionCode region,
                                                 LocalDate tripStart, LocalDate tripEnd) {
        List<FestivalSuggestion> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (JsonNode item : items) {
            if (!matchesRegion(item, region)) {
                continue;
            }
            LocalDate eventStart = parseDate(text(item, "eventstartdate", "eventStartDate"));
            LocalDate eventEnd = parseDate(text(item, "eventenddate", "eventEndDate"));
            if (eventStart == null || eventEnd == null) {
                continue;
            }
            boolean overlaps = !tripStart.isAfter(eventEnd) && !eventStart.isAfter(tripEnd);
            if (!overlaps) {
                continue;
            }
            String contentId = text(item, "contentid", "contentId");
            String title = text(item, "title");
            if (contentId == null || title == null) {
                continue;
            }
            String typeId = text(item, "contenttypeid", "contentTypeId");
            String thumbnail = text(item, "firstimage", "firstImage");
            String homepageUrl = HomepageUrlExtractor.extract(text(item, "homepage"));
            result.add(FestivalSuggestion.builder()
                    .contentId(contentId)
                    .contentTypeId(typeId == null ? null : Integer.valueOf(typeId))
                    .placeName(title)
                    .thumbnailUrl(thumbnail)
                    .addr1(text(item, "addr1"))
                    .mapX(text(item, "mapx", "mapX"))
                    .mapY(text(item, "mapy", "mapY"))
                    .eventStartDate(text(item, "eventstartdate", "eventStartDate"))
                    .eventEndDate(text(item, "eventenddate", "eventEndDate"))
                    .homepageUrl(homepageUrl)
                    .build());
            if (result.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return result;
    }

    /**
     * 시·도 단위 지역 일치. searchFestival2가 전국 결과를 섞어 줘도 서울 축제가 부산 일정에 못 올라오게 한다.
     */
    static boolean matchesRegion(JsonNode item, RegionCode region) {
        if (item == null || region == null) {
            return false;
        }
        String itemRegn = text(item, "ldongregncd", "lDongRegnCd");
        if (itemRegn != null) {
            return itemRegn.equals(region.getLDongRegnCd());
        }
        String itemArea = text(item, "areacode", "areaCode");
        String expectedArea = TourApiAreaCodes.fromLDongRegnCd(region.getLDongRegnCd());
        if (itemArea != null && expectedArea != null && itemArea.equals(expectedArea)) {
            return true;
        }
        String addr = text(item, "addr1");
        return matchesSidoAddress(addr, region.getSidoName());
    }

    static boolean matchesSidoAddress(String addr1, String sidoName) {
        if (addr1 == null || sidoName == null || sidoName.isBlank()) {
            return false;
        }
        if (addr1.startsWith(sidoName)) {
            return true;
        }
        String stem = sidoStem(sidoName);
        if (stem.length() < 2) {
            return false;
        }
        return addr1.startsWith(stem + " ")
                || addr1.startsWith(stem + "광역")
                || addr1.startsWith(stem + "특별")
                || addr1.startsWith(stem + "도");
    }

    static String sidoStem(String sidoName) {
        if (sidoName == null) {
            return "";
        }
        return sidoName
                .replace("통합특별시", "")
                .replace("특별자치도", "")
                .replace("특별자치시", "")
                .replace("광역시", "")
                .replace("특별시", "")
                .trim();
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
                    String url = HomepageUrlExtractor.extract(common.path("homepage").asText(null));
                    if (url != null) {
                        festival.setHomepageUrl(url);
                    }
                    return festival;
                })
                .defaultIfEmpty(festival)
                .onErrorReturn(festival);
    }

    private static String text(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static LocalDate parseDate(String yyyyMMdd) {
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
