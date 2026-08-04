package com.windmill.repository;

import com.windmill.domain.TripRecord;
import com.windmill.domain.VisitRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRecordRepository extends JpaRepository<TripRecord, Long> {
    List<TripRecord> findBySessionUuid(String sessionUuid);

    List<TripRecord> findTop5ByItinerary_SignguFullCodeAndOverallRatingOrderByCompletedAtDesc(
            String signguFullCode, VisitRating overallRating);
}
