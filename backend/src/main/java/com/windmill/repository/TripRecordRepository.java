package com.windmill.repository;

import com.windmill.domain.CompanionType;
import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRecordRepository extends JpaRepository<TripRecord, Long> {
    List<TripRecord> findBySessionUuid(String sessionUuid);

    List<TripRecord> findTop5ByItinerary_SignguFullCodeAndOverallRatingOrderByCompletedAtDesc(
            String signguFullCode, VisitRating overallRating);

    /** CommunityScheduleService 집계 기준 - 최근 200건만 사용해 전체 스캔 방지 */
    List<TripRecord> findTop200ByItinerary_SignguFullCodeOrderByCompletedAtDesc(String signguFullCode);

    List<TripRecord> findTop200ByItinerary_SignguFullCodeAndItinerary_CompanionTypeOrderByCompletedAtDesc(
            String signguFullCode, CompanionType companionType);
}
