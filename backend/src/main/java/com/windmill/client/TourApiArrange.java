package com.windmill.client;

/**
 * KorService2 {@code arrange} 값.
 * <p>
 * P = 조회순(인기) + 대표이미지 필수. 지역 목록·키워드 검색의 기본 정렬.
 * C = 수정일순. E = 거리순(위치기반 전용).
 */
public final class TourApiArrange {

    /** 조회순(인기). 그 지역에서 많이 본 장소가 앞에 온다. */
    public static final String POPULAR = "P";
    /** 수정일순. 축제처럼 최신성이 더 중요할 때. */
    public static final String MODIFIED = "C";
    /** 거리순. locationBasedList2 전용. */
    public static final String DISTANCE = "E";

    private TourApiArrange() {
    }
}
