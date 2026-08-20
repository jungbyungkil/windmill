package com.windmill.domain;

/**
 * 동반유형 - 첫 화면에서 단일선택. 반려동물 동반 여부는 Itinerary.withPet으로 별도 관리.
 * SOLO/COUPLE/TRIO/FAMILY_4는 인원수가 1/2/3/4명으로 고정, EXTENDED_FAMILY(대가족)만 5~9명 범위를
 * 사용자가 직접 입력한다(2026-08-20 결정, 검증은 프런트 CreateTripScreen에서 처리).
 */
public enum CompanionType {
    SOLO,
    COUPLE,
    TRIO,
    FAMILY_4,
    EXTENDED_FAMILY
}
