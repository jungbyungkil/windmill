package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** Stage1(연관관광지 조회) 결과 - KorService2와는 이름(placeName) 기준으로만 연결 가능 */
@Data
@Builder
public class RelatedCandidate {
    private String placeName;      // rlteTatsNm
    private String categoryLcls;   // rlteCtgryLclsNm (관광지/음식/숙박)
    private String categoryMcls;
    private String categoryScls;
    private int rank;              // rlteRank (1~50, 낮을수록 연관성 높음)

    // 아래는 KorService2 조인 후 채워짐 (Stage2 진입 전 필수)
    private String contentId;
    private Integer contentTypeId;
    private String thumbnailUrl;   // firstimage - 없으면 null(프론트에서 플레이스홀더 표시)
    private Boolean businessOpen;  // Stage2 결과
    private Double crowdRate;      // Stage3 결과 (원본, 0~100)
}
