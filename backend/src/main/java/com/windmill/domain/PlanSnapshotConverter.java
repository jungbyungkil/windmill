package com.windmill.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.PlanSnapshot;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code Itinerary.originalSnapshot} JSON 직렬화. {@link DetailFactListConverter}와 동일한
 * "실패해도 조용히 null" 정책 - 스냅샷 파싱 실패가 일정 조회 자체를 막지 않도록.
 */
@Converter
public class PlanSnapshotConverter implements AttributeConverter<PlanSnapshot, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(PlanSnapshot attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("originalSnapshot 직렬화 실패", e);
        }
    }

    @Override
    public PlanSnapshot convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, PlanSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }
}
