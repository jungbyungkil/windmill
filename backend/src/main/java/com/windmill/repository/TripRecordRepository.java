package com.windmill.repository;

import com.windmill.domain.CompanionType;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitRating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TripRecordRepository extends JpaRepository<TripRecord, Long> {
    List<TripRecord> findBySessionUuid(String sessionUuid);

    /** 일정 상태(ACTIVE/ENDED) 판정 - 이미 여행 마무리 기록을 남겼는지 */
    boolean existsByItinerary_Id(Long itineraryId);

    /** 일정 삭제 시 함께 지울 마무리 기록 조회 */
    List<TripRecord> findByItinerary_Id(Long itineraryId);

    List<TripRecord> findTop5ByItinerary_SignguFullCodeAndOverallRatingOrderByCompletedAtDesc(
            String signguFullCode, VisitRating overallRating);

    /** CommunityScheduleService 집계 기준 - 최근 200건만 사용해 전체 스캔 방지 */
    List<TripRecord> findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc(String signguFullCode);

    List<TripRecord> findTop200ByItinerary_SignguFullCodeAndItinerary_CompanionTypeOrderByCompletedAtDesc(
            String signguFullCode, CompanionType companionType);

    /** 첫 화면 피드 - 당일치기만 (평점↑ · 좋아요↑ · 클릭↑) */
    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
            ORDER BY
              CASE t.overallRating
                WHEN com.windmill.domain.VisitRating.GOOD THEN 0
                WHEN com.windmill.domain.VisitRating.NEUTRAL THEN 1
                WHEN com.windmill.domain.VisitRating.BAD THEN 2
                ELSE 3
              END,
              t.likeCount DESC,
              t.clickCount DESC,
              t.completedAt DESC
            """)
    List<TripRecord> findDayTripStories(Pageable pageable);

    /** 선택 지역 당일치기 피드 - 해당 지역만, 평점·좋아요·클릭 우선 */
    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.signguFullCode = :signguFullCode
              AND i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
            ORDER BY
              CASE t.overallRating
                WHEN com.windmill.domain.VisitRating.GOOD THEN 0
                WHEN com.windmill.domain.VisitRating.NEUTRAL THEN 1
                WHEN com.windmill.domain.VisitRating.BAD THEN 2
                ELSE 3
              END,
              t.likeCount DESC,
              t.clickCount DESC,
              t.completedAt DESC
            """)
    List<TripRecord> findDayTripStoriesByRegion(
            @Param("signguFullCode") String signguFullCode,
            Pageable pageable);

    /** 지역별 당일치기 GOOD 추천 - 인기순 */
    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.signguFullCode = :signguFullCode
              AND t.overallRating = :rating
              AND i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
            ORDER BY t.likeCount DESC, t.clickCount DESC, t.completedAt DESC
            """)
    List<TripRecord> findDayTripHighlights(
            @Param("signguFullCode") String signguFullCode,
            @Param("rating") VisitRating rating,
            Pageable pageable);

    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.startDate IS NULL
               OR i.endDate IS NULL
               OR i.startDate <> i.endDate
            """)
    List<TripRecord> findMultiDayOrInvalidRecords();
}
