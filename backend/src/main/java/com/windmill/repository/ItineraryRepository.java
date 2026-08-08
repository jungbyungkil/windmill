package com.windmill.repository;

import com.windmill.domain.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
    List<Itinerary> findBySessionUuid(String sessionUuid);

    java.util.Optional<Itinerary> findByShareToken(String shareToken);

    /**
     * 세션의 미완료 당일치기 - TripRecord가 없는 일정만 (여행 마무리 전).
     * 날짜·생성순으로 정렬해 메인에서 2·3·4… 줄로 이어하기에 쓴다.
     */
    @Query("""
            SELECT i FROM Itinerary i
            WHERE i.sessionUuid = :sessionUuid
              AND i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
              AND NOT EXISTS (
                SELECT t FROM TripRecord t WHERE t.itinerary = i
              )
            ORDER BY i.startDate ASC, i.createdAt ASC
            """)
    List<Itinerary> findOngoingDayTripsBySession(@Param("sessionUuid") String sessionUuid);
}
