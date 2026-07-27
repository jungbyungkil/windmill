package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * data.go.kr 공공데이터포털 공통 응답 포맷 파서.
 * items가 0건일 때 빈 문자열, 1건일 때 단일 객체, N건일 때 배열로 오는 것을 방어적으로 처리한다.
 */
@Slf4j
public final class KtoApiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KtoApiResponseParser() {
    }

    public static List<JsonNode> parseItems(String rawJson) {
        List<JsonNode> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode header = root.path("response").path("header");
            String resultCode = header.path("resultCode").asText("");
            if (!resultCode.isBlank() && !"0000".equals(resultCode) && !"00".equals(resultCode)) {
                log.warn("KTO API 오류 응답 - code: {}, msg: {}", resultCode, header.path("resultMsg").asText());
                return result;
            }

            JsonNode itemsNode = root.path("response").path("body").path("items");
            if (itemsNode.isMissingNode() || itemsNode.isNull()) {
                return result;
            }
            JsonNode item = itemsNode.path("item");
            if (item.isMissingNode() || item.isNull()) {
                return result;
            }
            if (item.isArray()) {
                item.forEach(result::add);
            } else if (item.isObject()) {
                result.add(item);
            }
        } catch (Exception e) {
            log.error("KTO API 응답 파싱 실패: {}", e.getMessage());
        }
        return result;
    }

    public static int parseTotalCount(String rawJson) {
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            return root.path("response").path("body").path("totalCount").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
