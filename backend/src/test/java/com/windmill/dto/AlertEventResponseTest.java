package com.windmill.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.windmill.domain.AlertEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEventResponseTest {

    @Test
    void from_treatsNaiveCreatedAtAsUtcAndEmitsKstOffset() {
        AlertEvent event = AlertEvent.builder()
                .id(1L)
                .itineraryId(9L)
                .kind("DAY_END")
                .level(TriggerLevel.NORMAL)
                .icon("🟢")
                .headline("🟢 오늘 모든 일정을 마칩니다")
                .createdAt(LocalDateTime.of(2026, 9, 4, 5, 25))
                .build();

        AlertEventResponse response = AlertEventResponse.from(event);

        assertEquals(LocalDateTime.of(2026, 9, 4, 14, 25), response.getCreatedAt().toLocalDateTime());
        assertEquals("+09:00", response.getCreatedAt().getOffset().toString());
    }

    @Test
    void json_includesKstOffsetSoBrowsersDoNotAssumeLocalNaiveTime() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AlertEvent event = AlertEvent.builder()
                .id(1L)
                .itineraryId(9L)
                .kind("DAY_END")
                .level(TriggerLevel.NORMAL)
                .icon("🟢")
                .headline("h")
                .createdAt(LocalDateTime.of(2026, 9, 4, 5, 25))
                .build();

        String json = mapper.writeValueAsString(AlertEventResponse.from(event));
        assertTrue(json.contains("2026-09-04T14:25:00+09:00"), json);
    }
}
