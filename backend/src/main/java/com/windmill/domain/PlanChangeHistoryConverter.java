package com.windmill.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.PlanChangeEntry;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code Itinerary.changeHistory}(변경 이력 FIFO 배열) JSON 직렬화. 파싱 실패 시 빈 배열 -
 * 손상된 이력 한 건이 일정 조회를 막지 않도록({@link DetailFactListConverter}와 동일 정책).
 */
@Converter
public class PlanChangeHistoryConverter implements AttributeConverter<List<PlanChangeEntry>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<PlanChangeEntry>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<PlanChangeEntry> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("changeHistory 직렬화 실패", e);
        }
    }

    @Override
    public List<PlanChangeEntry> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<PlanChangeEntry> list = MAPPER.readValue(dbData, TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
