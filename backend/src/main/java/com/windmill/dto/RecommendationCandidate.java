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
    /** "HH:mm" 영업 종료. 선택 게이트·카드 표시용 */
    private String closeTime;
    private String useTimeText;
    private String homepageUrl;     // 공식 홈페이지 URL, 없으면 null
    private Double distanceKm;      // 이전 장소와의 구간 거리(스마트 플랜) 또는 origin 기준 거리
    private String mapX;            // 경도 - 동선 최적화용
    private String mapY;            // 위도 - 동선 최적화용
    private String visitDate;       // 스마트 플랜 다일 배정용 "YYYY-MM-DD"
    private Boolean businessOpen;   // Stage2 결과 - RelatedCandidate에서 그대로 넘어옴, 모르면 null
    private List<Badge> badges;     // 날씨/혼잡/영업 실시간 상태 배지 - RecommendationPipeline에서 조립
}
