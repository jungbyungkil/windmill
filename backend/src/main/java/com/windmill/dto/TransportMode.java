package com.windmill.dto;

/**
 * 이동수단 - CAR는 카카오모빌리티 실제 도로 경로, WALK/TRANSIT은 전용 API 제휴 전이라
 * 직선거리 기반 추정치(KakaoDirectionsClient의 speedEstimate)로 대체한다.
 */
public enum TransportMode {
    CAR, WALK, TRANSIT
}
