package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 앱 실행 시 현재 위치 기반 상황 요약 */
@Data
@Builder
public class SituationSummaryResponse {
    private String regionCode;
    private String regionDisplayName;
    private Double temperature;
    private Double precipitationProbability;
    private String weatherLabel;
    private boolean heatAlert;
    private boolean rainAlert;
    private Integer crowdedPlaceCount;
    private String headline;
    private String detail;
    private List<String> tips;

    /** 넛지 카드 타입/우선순위 - 동시에 여러 트리거가 있어도 이 하나만 대표로 노출한다 */
    private NudgeType nudgeType;
    private int priority;
    /** 대표 문구 - tips 중 nudgeType에 해당하는 한 줄 */
    private String message;
    /** (타입+날짜+지역) 조합 - 같은 조건이면 같은 값, 프론트 dismiss 저장 키로 쓴다 */
    private String nudgeId;
    private boolean dismissible;
}
