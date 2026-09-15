package com.windmill.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.PendingProposal;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code Itinerary.pendingProposal} JSON 직렬화. 파싱 실패 시 null -
 * 손상된 제안 한 건이 일정 조회를 막지 않도록({@link PlanChangeHistoryConverter}와 동일 정책).
 */
@Converter
public class PendingProposalConverter implements AttributeConverter<PendingProposal, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(PendingProposal attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("pendingProposal 직렬화 실패", e);
        }
    }

    @Override
    public PendingProposal convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, PendingProposal.class);
        } catch (Exception e) {
            return null;
        }
    }
}
