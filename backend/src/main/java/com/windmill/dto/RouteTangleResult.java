package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 동선 꼬임 감지 결과 - trigger-status에 함께 실어 프론트가 자동 재배치를 제안한다 */
@Data
@Builder
public class RouteTangleResult {
    private boolean tangled;
    /** Haversine 기준(항상 있음). 카카오 실제 도로 데이터를 못 구하면 화면 표시도 이 값을 그대로 쓴다. */
    private Double currentDistanceKm;
    private Double optimizedDistanceKm;
    /** 카카오 실제 도로 소요시간(분) - 구했을 때만 채워짐(선택). 화면 표시는 이 값을 분 단위로 우선 노출. */
    private Integer currentDurationMinutes;
    private Integer optimizedDurationMinutes;
    private double wasteRatio;
    private String message;
    /** 지금 순서 장소명(꼬였을 때만 채움) - 알림 문구·배너 미리보기용 */
    private List<String> currentOrderPlaceNames;
    /** 재배치 순서 장소명(꼬였을 때만 채움) */
    private List<String> optimizedOrderPlaceNames;
    /** currentDurationMinutes - optimizedDurationMinutes (둘 다 있을 때만) - 배너 노출 임계값(10분) 판정용 */
    private Integer savingsMinutes;
}
