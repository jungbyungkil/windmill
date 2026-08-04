package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 4단계 파이프라인 최종 출력 - 프론트 추천 카드에 대응 */
@Data
@Builder
public class RecommendationCandidate {
    private String contentId;
    private Integer contentTypeId;     // 일정에 담을 때(addItem)/거리계산 origin으로 넘길 때 필요
    private String placeName;
    private String category;
    private String thumbnailUrl;       // firstimage, 없으면 null
    private Double crowdRate;          // 원본 집중률 (0~100), 없으면 null
    private Double freeRatePercent;    // 여유율 = 100 - crowdRate (응답 시점 가공)
    private List<String> matchedTags;  // Stage4 LLM 매칭 결과
    private String oneLiner;           // Stage4 LLM 생성 한 문장
    private int rank;                  // 연관순위 (Stage1)
    private String suggestedTime;      // InitialPlanService(5단계, 초안 배치)에서만 채워짐 - "HH:mm"

    // 아래는 Stage2(detailCommon2/detailIntro2)에서 채워짐 - 필수 카드 정보(위치/전화/요금) + 정기휴무
    private String addr1;
    private String tel;
    private Boolean isFree;         // 무료 여부 추정, 모르면 null(단정하지 않음)
    private String useFeeText;      // 이용요금 원문 텍스트
    private String restDateText;    // 정기휴무 원문 텍스트
    private Double distanceKm;      // origin(보통 마지막으로 담은 장소) 기준 직선거리, origin 없으면 null
}
