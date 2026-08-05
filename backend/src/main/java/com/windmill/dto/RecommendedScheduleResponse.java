package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 여행기록 기반 지역 추천 일정 - basedOnRecordCount가 콜드스타트 임계값 미만이면 days가 빈 리스트로 온다 */
@Data
@Builder
public class RecommendedScheduleResponse {
    private String regionName;
    private int basedOnRecordCount;
    private List<RecommendedDayResponse> days;
}
