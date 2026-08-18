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
    private BusinessStatus businessStatus; // Stage2 결과 - 휴무/영업종료 구분(businessOpen=false일 때 이유)

    // 아래는 Stage2에서 TourAttractionService.getDetail() 조회 결과로 채워짐 (위치/전화/요금/정기휴무 카드 표시용)
    private String addr1;
    private String tel;
    private String mapX;           // 경도(longitude), WGS84
    private String mapY;           // 위도(latitude), WGS84
    private String useFeeText;     // 이용요금 원문 텍스트 (예: "무료", "성인 3,000원")
    private Boolean isFree;        // useFeeText로 추정한 무료 여부 - 모르면 null
    private Integer estimatedCostPerPerson; // useFeeText로 추정한 1인 기준 비용(원) - 모르면 null
    private String restDateText;   // 정기휴무 원문 텍스트 (예: "매주 월요일")
    /** Stage2: "HH:mm" 영업 종료 시각. 파싱 실패 시 null */
    private String closeTime;
    /** Stage2: 이용시간 원문 */
    private String useTimeText;
    private String homepageUrl;    // detailCommon2 homepage에서 추출(HomepageUrlExtractor), 없으면 null
    private Double distanceKm;     // RecommendationPipeline에서 origin이 있을 때만 채워짐
    private String strollerText;   // chkbabycarriage 계열 원문
    private Boolean strollerFriendly; // "가능"/"불가능" 추정 - 모르면 null
    private boolean accessibleFriendly; // overview/카테고리 텍스트 키워드 매칭 근사(휴리스틱, 단정 아님)
    private String ageRangeText;   // expagerange 계열 원문("만 7세 이상" 등), 없으면 null

    private Double crowdRate;      // Stage3 결과 (원본, 0~100)
}
