package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 4단계 파이프라인 최종 출력 - 프론트 추천 카드에 대응 */
@Data
@Builder
public class RecommendationCandidate {
    private String contentId;
    private String placeName;
    private String category;
    private Double crowdRate;          // 원본 집중률 (0~100), 없으면 null
    private Double freeRatePercent;    // 여유율 = 100 - crowdRate (응답 시점 가공)
    private List<String> matchedTags;  // Stage4 LLM 매칭 결과
    private String oneLiner;           // Stage4 LLM 생성 한 문장
    private int rank;                  // 연관순위 (Stage1)
    private String suggestedTime;      // InitialPlanService(5단계, 초안 배치)에서만 채워짐 - "HH:mm"
}
