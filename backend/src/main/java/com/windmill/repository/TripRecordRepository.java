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

    List<TripRecord> findTop5ByItinerary_SignguFullCodeAndOverallRatingOrderByCompletedAtDesc(
            String signguFullCode, VisitRating overallRating);

    /** CommunityScheduleService 집계 기준 - 최근 200건만 사용해 전체 스캔 방지 */
    List<TripRecord> findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc(String signguFullCode);

    List<TripRecord> findTop200ByItinerary_SignguFullCodeAndItinerary_CompanionTypeOrderByCompletedAtDesc(
            String signguFullCode, CompanionType companionType);

    /** 첫 화면 피드 - 당일치기만 (startDate = endDate) */
    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
            ORDER BY t.likeCount DESC, t.clickCount DESC, t.completedAt DESC
            """)
    List<TripRecord> findDayTripStories(Pageable pageable);

    /** 지역별 당일치기 GOOD 추천 */
    @Query("""
            SELECT t FROM TripRecord t
            JOIN t.itinerary i
            WHERE i.signguFullCode = :signguFullCode
              AND t.overallRating = :rating
              AND i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
            ORDER BY t.completedAt DESC
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
