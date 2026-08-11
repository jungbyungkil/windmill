package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.service.ai.OpenAiService;
import com.windmill.util.ClosingTimeGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 5단계(초기 일정 초안 / 오토플랜) - Stage1~4 후보에 방문 시각을 배정한다.
 * closeTime이 있으면 마감 1시간 전(ClosingTimeGate) 안에 들어가도록 자동 조정하고,
 * 맞출 수 없으면 해당 후보는 초안에서 제외한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialPlanService {

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final int DEFAULT_INTERVAL_MINUTES = 90;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationPipeline recommendationPipeline;
    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<List<RecommendationCandidate>> draft(RecommendationRequest request, int placeCount) {
        int fetch = Math.max(placeCount * 2, placeCount + 3);
        return recommendationPipeline.recommend(request)
                .map(candidates -> candidates.stream()
                        .limit(Math.max(fetch, 1))
                        .collect(Collectors.toList()))
                .flatMap(list -> assignSchedule(list, Math.max(placeCount, 1)));
    }

    private Mono<List<RecommendationCandidate>> assignSchedule(List<RecommendationCandidate> candidates, int placeCount) {
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        if (!openAiService.isConfigured()) {
            return Mono.just(autoScheduleRespectingClose(candidates, placeCount));
        }
        return openAiService.complete(buildPrompt(candidates))
                .map(response -> finalizeWithCloseGate(applySuggestedTimes(response, candidates), candidates, placeCount))
                .onErrorReturn(autoScheduleRespectingClose(candidates, placeCount));
    }

    private String buildPrompt(List<RecommendationCandidate> candidates) {
        String lines = candidates.stream()
                .map(c -> {
                    String close = c.getCloseTime() == null ? "마감 미상" : "마감 " + c.getCloseTime();
                    return String.format("- %s (%s, %s)", c.getPlaceName(),
                            c.getCategory() == null ? "분류없음" : c.getCategory(), close);
                })
                .collect(Collectors.joining("\n"));
        return String.format("""
                아래는 이미 검증된 실제 관광지 후보 목록입니다. 새로운 장소를 만들지 말고,
                이 목록에 있는 장소만 가지고 하루 여행 코스로 자연스러운 방문 순서와
                방문 시각(HH:mm, 09:00~19:00 사이)을 정해주세요.
                마감 시각이 있는 장소는 반드시 마감 1시간 전까지 도착하도록 배정하세요.
                (예: 마감 17:00 → 16:00 이전 시각만 허용)

                관광지 목록:
                %s

                아래 JSON 배열 형식으로만 반환하세요. 다른 설명은 하지 마세요.
                [
                  {"placeName": "장소명", "suggestedTime": "HH:mm"}
                ]
                """, lines);
    }

    private List<RecommendationCandidate> applySuggestedTimes(String response, List<RecommendationCandidate> candidates) {
        try {
            String json = OpenAiService.stripJsonFence(response);
            JsonNode array = objectMapper.readTree(json);
            Map<String, String> timeByName = new HashMap<>();
            array.forEach(node -> timeByName.put(node.path("placeName").asText(""), node.path("suggestedTime").asText(null)));

            for (RecommendationCandidate c : candidates) {
                c.setSuggestedTime(timeByName.get(c.getPlaceName()));
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[InitialPlan] OpenAI 응답 파싱 실패: {}", e.getMessage());
            candidates.forEach(c -> c.setSuggestedTime(null));
            return candidates;
        }
    }

    private List<RecommendationCandidate> finalizeWithCloseGate(List<RecommendationCandidate> withTimes,
                                                                List<RecommendationCandidate> pool,
                                                                int placeCount) {
        boolean anyMissing = withTimes.stream()
                .anyMatch(c -> c.getSuggestedTime() == null || c.getSuggestedTime().isBlank());
        if (anyMissing) {
            return autoScheduleRespectingClose(pool, placeCount);
        }

        List<RecommendationCandidate> kept = new ArrayList<>();
        for (RecommendationCandidate c : sortByTime(withTimes)) {
            if (!acceptOrAdjust(c, kept)) {
                continue;
            }
            kept.add(c);
            if (kept.size() >= placeCount) {
                break;
            }
        }
        if (kept.size() < placeCount) {
            Set<String> used = kept.stream()
                    .map(RecommendationCandidate::getContentId)
                    .filter(id -> id != null)
                    .collect(Collectors.toCollection(HashSet::new));
            List<RecommendationCandidate> rest = pool.stream()
                    .filter(c -> c.getContentId() == null || !used.contains(c.getContentId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            LocalTime cursor = nextCursor(kept);
            for (RecommendationCandidate c : rest) {
                if (kept.size() >= placeCount) {
                    break;
                }
                c.setSuggestedTime(cursor.format(TIME_FORMAT));
                if (!acceptOrAdjust(c, kept)) {
                    continue;
                }
                kept.add(c);
                cursor = ClosingTimeGate.parseHhMm(c.getSuggestedTime()).plusMinutes(DEFAULT_INTERVAL_MINUTES);
            }
        }
        return sortByTime(kept);
    }

    /**
     * 마감을 자동 반영한 순차 배정. 불가 후보는 건너뛴다.
     */
    private List<RecommendationCandidate> autoScheduleRespectingClose(List<RecommendationCandidate> candidates, int placeCount) {
        List<RecommendationCandidate> out = new ArrayList<>();
        LocalTime cursor = DEFAULT_START;
        for (RecommendationCandidate c : candidates) {
            if (out.size() >= placeCount) {
                break;
            }
            c.setSuggestedTime(cursor.format(TIME_FORMAT));
            if (!acceptOrAdjust(c, out)) {
                continue;
            }
            out.add(c);
            LocalTime placed = ClosingTimeGate.parseHhMm(c.getSuggestedTime());
            cursor = (placed != null ? placed : cursor).plusMinutes(DEFAULT_INTERVAL_MINUTES);
        }
        return out;
    }

    /**
     * 제안 시각이 마감 게이트를 통과하면 true.
     * 막히면 가능한 가장 늦은 안전 시각으로 보정 시도. 보정 불가면 false(제외).
     */
    private boolean acceptOrAdjust(RecommendationCandidate c, List<RecommendationCandidate> already) {
        LocalTime arrival = ClosingTimeGate.parseHhMm(c.getSuggestedTime());
        if (arrival == null) {
            arrival = nextCursor(already);
            c.setSuggestedTime(arrival.format(TIME_FORMAT));
        }
        LocalTime close = resolveClose(c);
        ClosingTimeGate.CheckResult check = ClosingTimeGate.check(close, arrival);
        if (!check.blocked()) {
            return true;
        }
        LocalTime safe = latestSafeArrival(close);
        if (safe == null) {
            log.info("[InitialPlan] '{}' 마감 정보로 배정 불가 — 제외", c.getPlaceName());
            return false;
        }
        if (!already.isEmpty()) {
            LocalTime prev = ClosingTimeGate.parseHhMm(already.get(already.size() - 1).getSuggestedTime());
            if (prev != null && !safe.isAfter(prev.plusMinutes(30))) {
                log.info("[InitialPlan] '{}' 마감 보정 시각이 이전 일정과 겹침 — 제외", c.getPlaceName());
                return false;
            }
        }
        if (safe.isBefore(DEFAULT_START)) {
            log.info("[InitialPlan] '{}' 안전 도착 시각이 너무 이름 — 제외", c.getPlaceName());
            return false;
        }
        c.setSuggestedTime(safe.format(TIME_FORMAT));
        log.info("[InitialPlan] '{}' 마감({}) → 방문 시각 자동 보정 {}", c.getPlaceName(), c.getCloseTime(), c.getSuggestedTime());
        return true;
    }

    private static LocalTime resolveClose(RecommendationCandidate c) {
        LocalTime close = ClosingTimeGate.parseHhMm(c.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(c.getUseTimeText());
        }
        return close;
    }

    /** 마감−버퍼보다 1분 이른 시각 (= 게이트 통과) */
    private static LocalTime latestSafeArrival(LocalTime close) {
        if (close == null) {
            return null;
        }
        return close.minusMinutes(BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES + 1L);
    }

    private static LocalTime nextCursor(List<RecommendationCandidate> kept) {
        if (kept.isEmpty()) {
            return DEFAULT_START;
        }
        LocalTime last = ClosingTimeGate.parseHhMm(kept.get(kept.size() - 1).getSuggestedTime());
        if (last == null) {
            return DEFAULT_START;
        }
        return last.plusMinutes(DEFAULT_INTERVAL_MINUTES);
    }

    private List<RecommendationCandidate> sortByTime(List<RecommendationCandidate> candidates) {
        return candidates.stream()
                .filter(c -> c.getSuggestedTime() != null && !c.getSuggestedTime().isBlank())
                .sorted((a, b) -> a.getSuggestedTime().compareTo(b.getSuggestedTime()))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
