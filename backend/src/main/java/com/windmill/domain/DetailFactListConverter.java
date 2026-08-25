package com.windmill.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.DetailFact;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

@Converter
public class DetailFactListConverter implements AttributeConverter<List<DetailFact>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<DetailFact>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<DetailFact> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("detailFacts 직렬화 실패", e);
        }
    }

    @Override
    public List<DetailFact> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<DetailFact> list = MAPPER.readValue(dbData, TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
