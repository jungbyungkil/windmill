package com.windmill.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TourAttractionSummary {
    private String contentId;
    private String contentTypeId;
    private String title;
    private String addr1;
    private String addr2;
    private String mapX;
    private String mapY;
    private String firstImage;
    private String firstImage2;
    private String tel;
    /** TourAPI 표준분류 소분류 코드(예: 음식점 "A05020300"=일식) - 가족(자녀 동반) 음식점 순위 보정용 */
    private String cat3;

    public static TourAttractionSummary fromJson(JsonNode node) {
        return TourAttractionSummary.builder()
                .contentId(node.path("contentid").asText(null))
                .contentTypeId(node.path("contenttypeid").asText(null))
                .title(node.path("title").asText(null))
                .addr1(node.path("addr1").asText(null))
                .addr2(node.path("addr2").asText(null))
                .mapX(node.path("mapx").asText(null))
                .mapY(node.path("mapy").asText(null))
                .firstImage(node.path("firstimage").asText(null))
                .firstImage2(node.path("firstimage2").asText(null))
                .tel(node.path("tel").asText(null))
                .cat3(node.path("cat3").asText(null))
                .build();
    }
}
