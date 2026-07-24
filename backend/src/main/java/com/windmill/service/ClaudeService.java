package com.windmill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.AdjustRequest;
import com.windmill.dto.ScheduleItem;
import com.windmill.dto.TravelPlanRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    @Value("${claude.api.key:}")
    private String apiKey;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com")
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, List<ScheduleItem>> generateSchedule(TravelPlanRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return generateMockSchedule(request);
        }

        String prompt = buildSchedulePrompt(request);
        String response = callClaude(prompt);
        return parseScheduleFromResponse(response, request);
    }

    public Map<String, List<ScheduleItem>> adjustSchedule(AdjustRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return generateAdjustedMockSchedule(request);
        }

        String prompt = buildAdjustPrompt(request);
        String response = callClaude(prompt);
        return parseScheduleFromResponse2(response, request);
    }

    private String callClaude(String prompt) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", "claude-sonnet-4-6");
            body.put("max_tokens", 2000);
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            String responseBody = webClient.post()
                    .uri("/v1/messages")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.error("Claude API 호출 실패: {}", e.getMessage());
            return "";
        }
    }

    private String buildSchedulePrompt(TravelPlanRequest request) {
        return String.format("""
                여행 일정을 JSON 형식으로 만들어주세요.

                목적지: %s
                기간: %s ~ %s
                인원: %d명
                관심사: %s

                각 날짜별로 오전/오후/저녁 일정을 JSON 배열로 반환하세요.
                형식:
                {
                  "YYYY-MM-DD": [
                    {"time": "09:00", "place": "장소명", "activity": "활동내용", "address": "주소", "category": "관광/식사/숙박", "note": "팁"}
                  ]
                }

                JSON만 반환하고 다른 설명은 하지 마세요.
                """,
                request.getDestination(),
                request.getStartDate(),
                request.getEndDate(),
                request.getTravelers(),
                request.getInterests() != null ? String.join(", ", request.getInterests()) : "일반관광"
        );
    }

    private String buildAdjustPrompt(AdjustRequest request) {
        try {
            String currentJson = objectMapper.writeValueAsString(request.getCurrentSchedule());
            return String.format("""
                    현재 여행 일정을 실시간 상황에 맞게 재조정해주세요.

                    목적지: %s
                    현재 날짜: %s
                    현재 위치: %s
                    상황: %s
                    날씨: %s

                    현재 일정:
                    %s

                    위 상황을 고려해 일정을 재조정한 JSON을 반환하세요. 형식은 동일하게 유지하세요.
                    JSON만 반환하고 다른 설명은 하지 마세요.
                    """,
                    request.getDestination(),
                    request.getDate(),
                    request.getCurrentLocation() != null ? request.getCurrentLocation() : "미입력",
                    request.getSituation() != null ? request.getSituation() : "없음",
                    request.getWeatherCondition() != null ? request.getWeatherCondition() : "맑음",
                    currentJson
            );
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, List<ScheduleItem>> parseScheduleFromResponse(String response, TravelPlanRequest request) {
        try {
            if (response != null && !response.isBlank()) {
                String json = response.trim();
                if (json.startsWith("```")) {
                    json = json.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
                }
                JsonNode node = objectMapper.readTree(json);
                Map<String, List<ScheduleItem>> result = new LinkedHashMap<>();
                node.fields().forEachRemaining(entry -> {
                    List<ScheduleItem> items = new ArrayList<>();
                    entry.getValue().forEach(item -> items.add(ScheduleItem.builder()
                            .time(item.path("time").asText())
                            .place(item.path("place").asText())
                            .activity(item.path("activity").asText())
                            .address(item.path("address").asText())
                            .category(item.path("category").asText())
                            .note(item.path("note").asText())
                            .build()));
                    result.put(entry.getKey(), items);
                });
                return result;
            }
        } catch (Exception e) {
            log.warn("Claude 응답 파싱 실패, 목업 반환: {}", e.getMessage());
        }
        return generateMockSchedule(request);
    }

    private Map<String, List<ScheduleItem>> parseScheduleFromResponse2(String response, AdjustRequest request) {
        try {
            if (response != null && !response.isBlank()) {
                String json = response.trim();
                if (json.startsWith("```")) {
                    json = json.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
                }
                JsonNode node = objectMapper.readTree(json);
                Map<String, List<ScheduleItem>> result = new LinkedHashMap<>();
                node.fields().forEachRemaining(entry -> {
                    List<ScheduleItem> items = new ArrayList<>();
                    entry.getValue().forEach(item -> items.add(ScheduleItem.builder()
                            .time(item.path("time").asText())
                            .place(item.path("place").asText())
                            .activity(item.path("activity").asText())
                            .address(item.path("address").asText())
                            .category(item.path("category").asText())
                            .note(item.path("note").asText())
                            .build()));
                    result.put(entry.getKey(), items);
                });
                return result;
            }
        } catch (Exception e) {
            log.warn("Claude 조정 응답 파싱 실패: {}", e.getMessage());
        }
        return generateAdjustedMockSchedule(request);
    }

    private Map<String, List<ScheduleItem>> generateMockSchedule(TravelPlanRequest request) {
        Map<String, List<ScheduleItem>> schedule = new LinkedHashMap<>();
        LocalDate current = request.getStartDate();
        while (!current.isAfter(request.getEndDate())) {
            String dateKey = current.format(DateTimeFormatter.ISO_LOCAL_DATE);
            schedule.put(dateKey, List.of(
                    ScheduleItem.builder().time("09:00").place(request.getDestination() + " 관광지 A").activity("오전 관광").address(request.getDestination()).category("관광").note("혼잡도 낮음").build(),
                    ScheduleItem.builder().time("12:00").place("현지 맛집").activity("점심 식사").address(request.getDestination()).category("식사").note("현지 특산물 추천").build(),
                    ScheduleItem.builder().time("14:00").place(request.getDestination() + " 관광지 B").activity("오후 관광").address(request.getDestination()).category("관광").note("사진 명소").build(),
                    ScheduleItem.builder().time("18:00").place("숙소").activity("체크인 및 저녁 식사").address(request.getDestination()).category("숙박").note("예약 확인 필요").build()
            ));
            current = current.plusDays(1);
        }
        return schedule;
    }

    private Map<String, List<ScheduleItem>> generateAdjustedMockSchedule(AdjustRequest request) {
        Map<String, List<ScheduleItem>> adjusted = new LinkedHashMap<>();
        request.getCurrentSchedule().forEach((date, items) -> {
            List<ScheduleItem> newItems = new ArrayList<>();
            for (ScheduleItem item : items) {
                if ("관광".equals(item.getCategory()) && "비".equals(request.getWeatherCondition())) {
                    newItems.add(ScheduleItem.builder()
                            .time(item.getTime())
                            .place("실내 박물관")
                            .activity("비 때문에 실내 일정으로 변경")
                            .address(request.getDestination())
                            .category("관광")
                            .note("날씨 변수 대응 - 실내 대안")
                            .build());
                } else {
                    newItems.add(item);
                }
            }
            adjusted.put(date, newItems);
        });
        return adjusted;
    }

    public String generateAiSummary(String destination, int days) {
        return String.format("%s %d박 %d일 여행 일정이 생성되었습니다. 실시간 날씨와 혼잡도에 따라 일정을 자동으로 재조정해 드립니다.",
                destination, days - 1, days);
    }
}
