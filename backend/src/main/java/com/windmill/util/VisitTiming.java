package com.windmill.util;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.service.recommendation.BusinessHoursEvaluator;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 일정 슬롯의 체류·마감·점유 구간. TourAPI에 장소별 평균 체류시간은 없어서 카테고리 기본값을 쓰고,
 * 마감은 파싱된 CLOSE가 있으면 그걸, 없으면 장소 유형별 기본 시각을 쓴다.
 *
 * <p>이동시간 버퍼는 겹침 판단에 넣지 않는다(카카오 모빌리티 비용 트레이드오프와 같은 축 — 점유는
 * "한 사람이 같은 시각에 두 장소에 있을 수 없다"만 본다).
 *
 * <p>시각은 전부 KST 벽시계 {@link LocalTime}(HH:mm). Instant/UTC와 비교하지 않는다.
 */
public final class VisitTiming {

    /** 관광/문화 기본 체류. TourAPI에 평균 체류 필드가 없어 하드코딩. */
    public static final int ATTRACTION_STAY_MINUTES = 75;
    public static final int MEAL_STAY_MINUTES = 60;
    /** 박물관·미술관 등 마감 미상일 때 (한국 문화시설 흔한 폐관) */
    public static final LocalTime DEFAULT_CLOSE_MUSEUM = LocalTime.of(17, 0);
    /** 그 외 관광 슬롯 마감 미상일 때 */
    public static final LocalTime DEFAULT_CLOSE_OTHER = LocalTime.of(18, 0);
    /**
     * 식사 슬롯 마감 미상일 때. 17/18시로 두면 저녁 창(17:00~19:30) 자체가 마감 게이트에 막힌다.
     */
    public static final LocalTime DEFAULT_CLOSE_MEAL = LocalTime.of(21, 0);
    public static final LocalTime DAY_START = LocalTime.of(9, 0);
    public static final LocalTime LATEST_START = LocalTime.of(20, 0);
    public static final int SUGGEST_STEP_MINUTES = 15;
    public static final int MAX_SUGGESTIONS = 3;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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
        if (contentTypeId != null && contentTypeId == 14) {
            return true;
        }
        String blob = join(placeName, category);
        if (tags != null) {
            blob = blob + " " + String.join(" ", tags);
        }
        return containsAny(blob, "박물관", "미술관", "갤러리", "전시관");
    }

    public static boolean isMuseumOrGallery(ItineraryItem item) {
        if (item == null) {
            return false;
        }
        return isMuseumOrGallery(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags());
    }

    /**
     * 정확한 CLOSE(스냅샷 또는 usetime 파싱)가 있으면 그걸 쓰고, 없으면 유형별 기본 마감.
     * 박물관·미술관 → 17:00, 식사 → 21:00, 그 외 → 18:00.
     */
    public static LocalTime resolveCloseTime(String closeTime, String useTimeText, Integer contentTypeId,
                                             String placeName, String category, List<String> tags) {
        LocalTime parsed = ClosingTimeGate.parseHhMm(closeTime);
        if (parsed == null) {
            parsed = BusinessHoursEvaluator.extractCloseTimeFromText(useTimeText);
        }
        if (parsed != null) {
            return parsed;
        }
        if (isMuseumOrGallery(contentTypeId, placeName, category, tags)) {
            return DEFAULT_CLOSE_MUSEUM;
        }
        if (isMeal(contentTypeId, placeName, category, tags)) {
            return DEFAULT_CLOSE_MEAL;
        }
        return DEFAULT_CLOSE_OTHER;
    }

    /** TourAPI에서 파싱된 마감이면 true. 17/18시 기본값은 추정이라 입장마감 1시간 버퍼를 붙이지 않는다. */
    public static boolean hasAccurateCloseTime(String closeTime, String useTimeText) {
        return ClosingTimeGate.parseHhMm(closeTime) != null
                || BusinessHoursEvaluator.extractCloseTimeFromText(useTimeText) != null;
    }

    public static boolean hasAccurateCloseTime(ItineraryItem item) {
        return item != null && hasAccurateCloseTime(item.getCloseTime(), item.getUseTimeText());
    }

    public static int closeBufferMinutes(String closeTime, String useTimeText) {
        return hasAccurateCloseTime(closeTime, useTimeText) ? BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES : 0;
    }

    public static LocalTime resolveCloseTime(ItineraryItem item) {
        if (item == null) {
            return DEFAULT_CLOSE_OTHER;
        }
        return resolveCloseTime(item.getCloseTime(), item.getUseTimeText(), item.getContentTypeId(),
                item.getPlaceName(), item.getCategory(), item.getTags());
    }

    public static LocalTime resolveCloseTime(AddItineraryItemRequest request) {
        if (request == null) {
            return DEFAULT_CLOSE_OTHER;
        }
        return resolveCloseTime(request.getCloseTime(), request.getUseTimeText(), request.getContentTypeId(),
                request.getPlaceName(), request.getCategory(), request.getTags());
    }

    public static int stayMinutes(Integer contentTypeId, String placeName, String category, List<String> tags) {
        return isMeal(contentTypeId, placeName, category, tags) ? MEAL_STAY_MINUTES : ATTRACTION_STAY_MINUTES;
    }

    public static int stayMinutes(ItineraryItem item) {
        if (item == null) {
            return ATTRACTION_STAY_MINUTES;
        }
        return stayMinutes(item.getContentTypeId(), item.getPlaceName(), item.getCategory(), item.getTags());
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
