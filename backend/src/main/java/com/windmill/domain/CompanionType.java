package com.windmill.domain;

/**
 * 동반유형 - 첫 화면에서 단일선택. 반려동물 동반 여부는 Itinerary.withPet으로 별도 관리.
 * EXTENDED_FAMILY(대가족) 기준 인원수는 5인 이상으로 정의(2026-08-04 결정, 코드상 검증 로직은 없음 - UI 라벨 구분용).
 */
public enum CompanionType {
    SOLO,
    COUPLE,
    FAMILY_4,
    EXTENDED_FAMILY
}
