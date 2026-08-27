package com.windmill.util;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.DetailFact;
import com.windmill.service.recommendation.BusinessHoursEvaluator;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일정 슬롯의 체류·마감·점유 구간. TourAPI에 장소별 평균 체류시간은 없어서 카테고리 기본값을 쓰고,
 * 마감은 TourAPI usetime/playtime에서 가장 늦은 끝 시각을 쓰고, 없으면 유형별 상식 기본값
 * (박물관 17시, 유료 전시 20시, 공연·축제 22시, 식사 21시 등)을 쓴다.
 *
 * <p>계획 체류는 "보통 이렇게 머문다" 값이고, 겹침 검사에는 더 짧은 최소 체류를 쓴다.
 * 카페 테이크아웃·짧은 이동·간단 식사를 앞뒤 일정 사이에 넣을 수 있게 하기 위함.
 * 이동시간은 점유 구간에 넣지 않는다({@link GeoUtils#estimateTravelMinutes}는 다음 슬롯 배치용).
 *
 * <p>시각은 전부 KST 벽시계 {@link LocalTime}(HH:mm). Instant/UTC와 비교하지 않는다.
 */
public final class VisitTiming {

    /** 관광 기본 체류. 포토스팟·짧은 둘러보기가 흔해 75분은 앞뒤 일정을 너무 밀어낸다. */
    public static final int ATTRACTION_STAY_MINUTES = 45;
    /** 가족 일정 관광 체류(스마트 동선). */
    public static final int FAMILY_ATTRACTION_STAY_MINUTES = 60;
    /** 카페·디저트. 테이크아웃·커피 한 잔을 전제로 짧게. */
    public static final int CAFE_STAY_MINUTES = 25;
    /** 간단 식사. 코스 만찬이 아니라 당일치기 점심/저녁. */
    public static final int MEAL_STAY_MINUTES = 40;
    /** 유료 전시·기획전 */
    public static final int EXHIBITION_STAY_MINUTES = 60;
    /** 공연·축제 — 실제 관람 시간이 있어 다른 유형보다 길게 */
    public static final int PERFORMANCE_STAY_MINUTES = 90;
    /** 박물관·기념관·도서관 등 마감 미상일 때 (한국 문화시설 흔한 폐관) */
    public static final LocalTime DEFAULT_CLOSE_MUSEUM = LocalTime.of(17, 0);
    /** 미술관·일반 문화시설 마감 미상일 때 */
    public static final LocalTime DEFAULT_CLOSE_GALLERY = LocalTime.of(18, 0);
    /** 그 외 관광 슬롯 마감 미상일 때 */
    public static final LocalTime DEFAULT_CLOSE_OTHER = LocalTime.of(18, 0);
    /** 유료 전시·전시관 — 저녁 관람이 흔함 */
    public static final LocalTime DEFAULT_CLOSE_EXHIBITION = LocalTime.of(20, 0);
    /**
     * 식사 슬롯 마감 미상일 때. 17/18시로 두면 저녁 창(17:00~19:30) 자체가 마감 게이트에 막힌다.
     */
    public static final LocalTime DEFAULT_CLOSE_MEAL = LocalTime.of(21, 0);
    /** 공연장·축제·야경 — 밤 슬롯이 본 일정인 경우가 많음 */
    public static final LocalTime DEFAULT_CLOSE_PERFORMANCE = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_CLOSE_SHOPPING = LocalTime.of(21, 0);
    public static final LocalTime DAY_START = LocalTime.of(9, 0);
    /** 제안·동선 재계산의 시작 상한. 전시/공연 저녁 슬롯(20시대)이 잘리지 않게 21시까지. */
    public static final LocalTime LATEST_START = LocalTime.of(21, 0);
    public static final int SUGGEST_STEP_MINUTES = 15;
    public static final int MAX_SUGGESTIONS = 3;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> MUSEUM_CAT3 = Set.of(
            "A02060100", "A02060200", "A02060900");
    private static final Set<String> GALLERY_CAT3 = Set.of("A02060500");
    private static final Set<String> EXHIBITION_CAT3 = Set.of("A02060300");
    private static final Set<String> PERFORMANCE_CAT3 = Set.of("A02060600", "A02061000");
    private static final Set<String> HOURS_FACT_KEYS = Set.of(
            "playtime", "usetime", "opentime", "usetimeculture", "usetimefestival",
            "usetimeleports", "opentimefood");
    private static final Set<String> STAY_FACT_KEYS = Set.of("spendtime", "spendtimefestival", "taketime", "playtime");
    private static final Pattern STAY_HOUR_MIN = Pattern.compile("(\\d+)\\s*시간\\s*(\\d+)\\s*분");
    private static final Pattern STAY_HOUR = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*시간");
    private static final Pattern STAY_MIN = Pattern.compile("(\\d+)\\s*분");
    private static final int MIN_STAY = 20;
    private static final int MAX_STAY = 180;
    private static final int PACKING_FLOOR_MINUTES = 15;
    private static final int PERFORMANCE_PACKING_FLOOR_MINUTES = 40;

    private VisitTiming() {
    }

    public static boolean isMeal(Integer contentTypeId, String placeName, String category, List<String> tags) {
        if (contentTypeId != null && contentTypeId == 39) {
            return !isCafe(placeName, category, tags);
        }
        if (tags != null) {
            for (String t : tags) {
                if ("#맛집".equals(t) || "#한식".equals(t) || "#중식".equals(t) || "#일식".equals(t) || "#양식".equals(t)) {
                    return true;
                }
            }
        }
        String blob = join(placeName, category);
        return containsAny(blob, "맛집", "식당", "음식점") && !isCafe(placeName, category, tags);
    }

    public static boolean isMeal(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        return isMeal(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags());
    }

    public static boolean isMuseumOrGallery(Integer contentTypeId, String placeName, String category,
                                            List<String> tags) {
        return isMuseum(contentTypeId, placeName, category, tags, null)
                || isGallery(contentTypeId, placeName, category, tags, null);
    }

    public static boolean isMuseumOrGallery(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        return isMuseumOrGallery(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags());
    }

    /**
     * 정확한 CLOSE(스냅샷·usetime·playtime 파싱)가 있으면 그걸 쓰고, 없으면 유형별 기본 마감.
     * 박물관 → 17:00, 미술관 → 18:00, 유료 전시 → 20:00, 공연/축제 → 22:00, 식사/카페/쇼핑 → 21:00,
     * 그 외 → 18:00.
     */
    public static LocalTime resolveCloseTime(String closeTime, String useTimeText, Integer contentTypeId,
                                             String placeName, String category, List<String> tags) {
        return resolveCloseTime(closeTime, useTimeText, contentTypeId, placeName, category, tags, null, null);
    }

    public static LocalTime resolveCloseTime(String closeTime, String useTimeText, Integer contentTypeId,
                                             String placeName, String category, List<String> tags,
                                             String cat3, List<DetailFact> detailFacts) {
        LocalTime parsed = parsedClose(closeTime, useTimeText, detailFacts);
        if (parsed != null) {
            return parsed;
        }
        return defaultClose(contentTypeId, placeName, category, tags, cat3);
    }

    /** TourAPI에서 파싱된 마감이면 true. 유형 기본값은 추정이라 입장마감 1시간 버퍼를 붙이지 않는다. */
    public static boolean hasAccurateCloseTime(String closeTime, String useTimeText) {
        return hasAccurateCloseTime(closeTime, useTimeText, null);
    }

    public static boolean hasAccurateCloseTime(String closeTime, String useTimeText, List<DetailFact> detailFacts) {
        return parsedClose(closeTime, useTimeText, detailFacts) != null;
    }

    public static boolean hasAccurateCloseTime(ItineraryItem item) {
        return item != null && hasAccurateCloseTime(item.getCloseTime(), item.getUseTimeText(), item.getDetailFacts());
    }

    public static int closeBufferMinutes(String closeTime, String useTimeText) {
        return closeBufferMinutes(closeTime, useTimeText, null);
    }

    public static int closeBufferMinutes(String closeTime, String useTimeText, List<DetailFact> detailFacts) {
        return hasAccurateCloseTime(closeTime, useTimeText, detailFacts) ? BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES : 0;
    }

    public static LocalTime resolveCloseTime(ItineraryItem item) {
        if (item == null) {
            return DEFAULT_CLOSE_OTHER;
        }
        return resolveCloseTime(item.getCloseTime(), item.getUseTimeText(), item.getContentTypeId(),
                item.getPlaceName(), item.getCategory(), item.getTags(), null, item.getDetailFacts());
    }

    public static LocalTime resolveCloseTime(AddItineraryItemRequest request) {
        if (request == null) {
            return DEFAULT_CLOSE_OTHER;
        }
        return resolveCloseTime(request.getCloseTime(), request.getUseTimeText(), request.getContentTypeId(),
                request.getPlaceName(), request.getCategory(), request.getTags(),
                request.getCat3(), request.getDetailFacts());
    }

    public static int stayMinutes(Integer contentTypeId, String placeName, String category, List<String> tags) {
        return stayMinutes(contentTypeId, placeName, category, tags, null, null);
    }

    public static int stayMinutes(Integer contentTypeId, String placeName, String category, List<String> tags,
                                  String cat3, List<DetailFact> detailFacts) {
        Integer fromApi = stayFromFacts(detailFacts);
        if (fromApi != null) {
            return fromApi;
        }
        if (isCafe(placeName, category, tags)) {
            return CAFE_STAY_MINUTES;
        }
        if (isMeal(contentTypeId, placeName, category, tags)) {
            return MEAL_STAY_MINUTES;
        }
        if (isPerformance(contentTypeId, placeName, category, tags, cat3)) {
            return PERFORMANCE_STAY_MINUTES;
        }
        if (isExhibition(contentTypeId, placeName, category, tags, cat3)
                && !isMuseum(contentTypeId, placeName, category, tags, cat3)
                && !isGallery(contentTypeId, placeName, category, tags, cat3)) {
            return EXHIBITION_STAY_MINUTES;
        }
        return ATTRACTION_STAY_MINUTES;
    }

    /**
     * 겹침 검사용 최소 체류. 계획 체류보다 짧게 잡아, 같은 시각만 아니면 카페·짧은 관광을
     * 기존 일정 사이에 넣을 수 있게 한다. 공연은 실제 관람이라 절반만 줄인다.
     */
    public static int packingStayMinutes(Integer contentTypeId, String placeName, String category, List<String> tags,
                                         String cat3, List<DetailFact> detailFacts) {
        int stay = stayMinutes(contentTypeId, placeName, category, tags, cat3, detailFacts);
        if (isPerformance(contentTypeId, placeName, category, tags, cat3)) {
            return Math.max(PERFORMANCE_PACKING_FLOOR_MINUTES, stay * 2 / 3);
        }
        return Math.max(PACKING_FLOOR_MINUTES, stay / 2);
    }

    public static int defaultPackingStayMinutes() {
        return Math.max(PACKING_FLOOR_MINUTES, ATTRACTION_STAY_MINUTES / 2);
    }

    public static int packingStayMinutes(ItineraryItem item) {
        if (item == null) {
            return defaultPackingStayMinutes();
        }
        return packingStayMinutes(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags(),
                null, item.getDetailFacts());
    }

    public static int packingStayMinutes(AddItineraryItemRequest request) {
        if (request == null) {
            return Math.max(PACKING_FLOOR_MINUTES, ATTRACTION_STAY_MINUTES / 2);
        }
        return packingStayMinutes(request.getContentTypeId(), request.getPlaceName(), request.getCategory(),
                request.getTags(), request.getCat3(), request.getDetailFacts());
    }

    public static int stayMinutes(ItineraryItem item) {
        if (item == null) {
            return ATTRACTION_STAY_MINUTES;
        }
        return stayMinutes(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags(),
                null, item.getDetailFacts());
    }

    public static int stayMinutes(AddItineraryItemRequest request) {
        if (request == null) {
            return ATTRACTION_STAY_MINUTES;
        }
        return stayMinutes(request.getContentTypeId(), request.getPlaceName(), request.getCategory(),
                request.getTags(), request.getCat3(), request.getDetailFacts());
    }

    /**
     * 점유 종료 = min(시작+체류, 마감). 마감을 넘기도록 체류를 잡지 않는다.
     * 자정 넘김은 당일치기 범위 밖이라 분 단위로 잘라 비교한다.
     */
    public static LocalTime occupancyEnd(LocalTime start, int stayMinutes, LocalTime close) {
        if (start == null) {
            return null;
        }
        int startMin = minutesOf(start);
        int endMin = startMin + Math.max(0, stayMinutes);
        if (close != null) {
            int closeMin = minutesOf(close);
            if (closeMin > startMin) {
                endMin = Math.min(endMin, closeMin);
            }
        }
        endMin = Math.min(endMin, 24 * 60 - 1);
        return LocalTime.of(endMin / 60, endMin % 60);
    }

    public static LocalTime occupancyEnd(ItineraryItem item) {
        if (item == null) {
            return null;
        }
        LocalTime start = ClosingTimeGate.parseHhMm(item.getScheduledTime());
        return occupancyEnd(start, stayMinutes(item), resolveCloseTime(item));
    }

    /** 겹침 검사용 점유 종료 — {@link #packingStayMinutes(ItineraryItem)} 기준. */
    public static LocalTime occupancyEndPacking(ItineraryItem item) {
        if (item == null) {
            return null;
        }
        LocalTime start = ClosingTimeGate.parseHhMm(item.getScheduledTime());
        return occupancyEnd(start, packingStayMinutes(item), resolveCloseTime(item));
    }

    /** 표준 구간 겹침: (A.시작 &lt; B.종료) AND (A.종료 &gt; B.시작). 끝점만 맞닿으면 겹치지 않음. */
    public static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * 겹침·마감을 피하는 대안 시작 시각. 선호 시각 뒤(기존 일정이 끝난 뒤)를 먼저 넣고, 모자라면 앞쪽.
     * 이동시간은 포함하지 않는다.
     */
    public static List<String> suggestAlternativeStarts(LocalTime preferred, LocalTime candidateClose,
                                                        int candidateStay, List<Occupied> occupied,
                                                        LocalTime dayStart, Long excludeItemId) {
        return suggestAlternativeStarts(preferred, candidateClose, candidateStay, occupied, dayStart,
                excludeItemId, BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES);
    }

    public static List<String> suggestAlternativeStarts(LocalTime preferred, LocalTime candidateClose,
                                                        int candidateStay, List<Occupied> occupied,
                                                        LocalTime dayStart, Long excludeItemId,
                                                        int closeBufferMinutes) {
        LocalTime from = dayStart != null ? dayStart : DAY_START;
        LocalTime latest = LATEST_START;
        int buf = Math.max(0, closeBufferMinutes);
        if (candidateClose != null) {
            LocalTime byClose = candidateClose.minusMinutes(buf);
            if (byClose.isBefore(latest)) {
                latest = byClose;
            }
        }
        if (!from.isBefore(latest)) {
            return List.of();
        }

        List<LocalTime> out = new ArrayList<>();
        collectSuggestions(out, ceilToStep(preferred == null ? from : preferred.plusMinutes(SUGGEST_STEP_MINUTES)),
                latest, preferred, candidateClose, candidateStay, occupied, excludeItemId, buf);
        collectSuggestions(out, ceilToStep(from), preferred == null ? latest : preferred,
                preferred, candidateClose, candidateStay, occupied, excludeItemId, buf);
        return out.stream().map(HH_MM::format).toList();
    }

    private static void collectSuggestions(List<LocalTime> out, LocalTime from, LocalTime until,
                                           LocalTime preferred, LocalTime candidateClose, int candidateStay,
                                           List<Occupied> occupied, Long excludeItemId, int closeBufferMinutes) {
        if (from == null || until == null || !from.isBefore(until)) {
            return;
        }
        for (LocalTime t = from; t != null && t.isBefore(until); t = t.plusMinutes(SUGGEST_STEP_MINUTES)) {
            if (out.size() >= MAX_SUGGESTIONS) {
                return;
            }
            if (t.equals(preferred)) {
                continue;
            }
            if (out.contains(t)) {
                continue;
            }
            if (ClosingTimeGate.check(candidateClose, t, closeBufferMinutes).blocked()) {
                continue;
            }
            LocalTime end = occupancyEnd(t, candidateStay, candidateClose);
            boolean hit = false;
            for (Occupied o : occupied) {
                if (excludeItemId != null && excludeItemId.equals(o.itemId())) {
                    continue;
                }
                if (overlaps(t, end, o.start(), o.end())) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                out.add(t);
            }
        }
    }

    private static LocalTime ceilToStep(LocalTime time) {
        if (time == null) {
            return null;
        }
        int m = minutesOf(time);
        int step = SUGGEST_STEP_MINUTES;
        int rounded = ((m + step - 1) / step) * step;
        if (rounded >= 24 * 60) {
            return null;
        }
        return LocalTime.of(rounded / 60, rounded % 60);
    }

    public static int minutesOf(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public record Occupied(Long itemId, String placeName, LocalTime start, LocalTime end) {
    }

    private static boolean isCafe(String placeName, String category, List<String> tags) {
        if (tags != null) {
            for (String t : tags) {
                if ("#카페".equals(t)) {
                    return true;
                }
            }
        }
        return containsAny(join(placeName, category), "카페", "커피", "디저트");
    }

    private static LocalTime parsedClose(String closeTime, String useTimeText, List<DetailFact> detailFacts) {
        LocalTime parsed = ClosingTimeGate.parseHhMm(closeTime);
        if (parsed != null) {
            return parsed;
        }
        return BusinessHoursEvaluator.extractCloseTimeFromText(mergeHoursText(useTimeText, detailFacts));
    }

    private static String mergeHoursText(String useTimeText, List<DetailFact> facts) {
        StringBuilder sb = new StringBuilder(useTimeText == null ? "" : useTimeText);
        if (facts != null) {
            for (DetailFact fact : facts) {
                if (fact == null || fact.getValue() == null || fact.getValue().isBlank()) {
                    continue;
                }
                String key = fact.getKey() == null ? "" : fact.getKey().trim().toLowerCase(Locale.ROOT);
                if (HOURS_FACT_KEYS.contains(key)) {
                    sb.append(' ').append(fact.getValue());
                }
            }
        }
        String merged = sb.toString().trim();
        return merged.isEmpty() ? null : merged;
    }

    private static LocalTime defaultClose(Integer contentTypeId, String placeName, String category,
                                          List<String> tags, String cat3) {
        if (isPerformance(contentTypeId, placeName, category, tags, cat3)
                || containsAny(blob(placeName, category, tags), "야경", "야시장", "야간개장", "야간 개장")) {
            return DEFAULT_CLOSE_PERFORMANCE;
        }
        if (isMuseum(contentTypeId, placeName, category, tags, cat3)) {
            return DEFAULT_CLOSE_MUSEUM;
        }
        if (isGallery(contentTypeId, placeName, category, tags, cat3)) {
            return DEFAULT_CLOSE_GALLERY;
        }
        if (isExhibition(contentTypeId, placeName, category, tags, cat3)) {
            return DEFAULT_CLOSE_EXHIBITION;
        }
        if (isMeal(contentTypeId, placeName, category, tags) || isCafe(placeName, category, tags)) {
            return DEFAULT_CLOSE_MEAL;
        }
        if (contentTypeId != null && contentTypeId == 38) {
            return DEFAULT_CLOSE_SHOPPING;
        }
        if (contentTypeId != null && contentTypeId == 14) {
            return DEFAULT_CLOSE_GALLERY;
        }
        return DEFAULT_CLOSE_OTHER;
    }

    private static boolean isPerformance(Integer contentTypeId, String placeName, String category,
                                         List<String> tags, String cat3) {
        if (contentTypeId != null && contentTypeId == 15) {
            return true;
        }
        if (cat3 != null && PERFORMANCE_CAT3.contains(cat3.trim())) {
            return true;
        }
        if (hasTag(tags, "#공연장")) {
            return true;
        }
        return containsAny(blob(placeName, category, tags),
                "공연장", "콘서트홀", "콘서트", "뮤지컬", "연극", "오페라", "무용공연",
                "예술의전당", "오페라하우스", "문화회관", "아트홀", "씨어터", "공연");
    }

    private static boolean isMuseum(Integer contentTypeId, String placeName, String category,
                                    List<String> tags, String cat3) {
        if (cat3 != null && MUSEUM_CAT3.contains(cat3.trim())) {
            return true;
        }
        if (hasTag(tags, "#박물관")) {
            return true;
        }
        return containsAny(blob(placeName, category, tags), "박물관", "뮤지엄", "기념관", "도서관", "과학관");
    }

    private static boolean isGallery(Integer contentTypeId, String placeName, String category,
                                     List<String> tags, String cat3) {
        if (cat3 != null && GALLERY_CAT3.contains(cat3.trim())) {
            return true;
        }
        if (hasTag(tags, "#미술관")) {
            return true;
        }
        return containsAny(blob(placeName, category, tags), "미술관", "갤러리");
    }

    private static boolean isExhibition(Integer contentTypeId, String placeName, String category,
                                        List<String> tags, String cat3) {
        if (cat3 != null && EXHIBITION_CAT3.contains(cat3.trim())) {
            return true;
        }
        if (hasTag(tags, "#전시")) {
            return true;
        }
        return containsAny(blob(placeName, category, tags), "전시관", "전시회", "기획전", "특별전", "유료전시", "전시");
    }

    private static Integer stayFromFacts(List<DetailFact> facts) {
        if (facts == null) {
            return null;
        }
        Integer fromSpend = null;
        Integer fromPlay = null;
        for (DetailFact fact : facts) {
            if (fact == null || fact.getValue() == null) {
                continue;
            }
            String key = fact.getKey() == null ? "" : fact.getKey().trim().toLowerCase(Locale.ROOT);
            if (!STAY_FACT_KEYS.contains(key)) {
                continue;
            }
            if ("playtime".equals(key)) {
                fromPlay = durationMinutes(fact.getValue());
            } else {
                Integer parsed = parseStayPhrase(fact.getValue());
                if (parsed != null) {
                    fromSpend = parsed;
                }
            }
        }
        return fromSpend != null ? fromSpend : fromPlay;
    }

    private static Integer durationMinutes(String hoursText) {
        LocalTime open = BusinessHoursEvaluator.extractOpenTimeFromText(hoursText);
        LocalTime close = BusinessHoursEvaluator.extractCloseTimeFromText(hoursText);
        if (open == null || close == null || !close.isAfter(open)) {
            return parseStayPhrase(hoursText);
        }
        int minutes = minutesOf(close) - minutesOf(open);
        if (minutes < MIN_STAY || minutes > MAX_STAY) {
            return null;
        }
        return minutes;
    }

    private static Integer parseStayPhrase(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher hm = STAY_HOUR_MIN.matcher(text);
        if (hm.find()) {
            return clampStay(Integer.parseInt(hm.group(1)) * 60 + Integer.parseInt(hm.group(2)));
        }
        Matcher h = STAY_HOUR.matcher(text);
        if (h.find()) {
            return clampStay((int) Math.round(Double.parseDouble(h.group(1)) * 60));
        }
        Matcher m = STAY_MIN.matcher(text);
        if (m.find()) {
            return clampStay(Integer.parseInt(m.group(1)));
        }
        return null;
    }

    private static Integer clampStay(int minutes) {
        if (minutes < MIN_STAY || minutes > MAX_STAY) {
            return null;
        }
        return minutes;
    }

    private static boolean hasTag(List<String> tags, String expected) {
        if (tags == null) {
            return false;
        }
        for (String t : tags) {
            if (expected.equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static String blob(String placeName, String category, List<String> tags) {
        String extra = tags == null ? "" : String.join(" ", tags);
        return join(placeName, category) + " " + extra.toLowerCase(Locale.ROOT);
    }

    private static String join(String a, String b) {
        return ((a == null ? "" : a) + " " + (b == null ? "" : b)).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String blob, String... needles) {
        if (blob == null || blob.isBlank()) {
            return false;
        }
        String hay = blob.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (hay.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
