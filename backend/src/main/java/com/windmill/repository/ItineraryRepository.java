package com.windmill.repository;

import com.windmill.domain.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
    List<Itinerary> findBySessionUuid(String sessionUuid);

    java.util.Optional<Itinerary> findByShareToken(String shareToken);

    /**
     * 세션의 미완료 당일치기 - TripRecord가 없고 오늘(KST) 이후 날짜인 일정만 (여행 마무리 전).
     * 지난 날짜 초안은 "진행 중인 여행" 목록에서 제외한다 - 내 여행 관리(ENDED)에서만 조회 가능.
     * 날짜·생성순으로 정렬해 메인에서 2·3·4… 줄로 이어하기에 쓴다.
     */
    @Query("""
            SELECT i FROM Itinerary i
            WHERE i.sessionUuid = :sessionUuid
              AND i.startDate IS NOT NULL
              AND i.endDate IS NOT NULL
              AND i.startDate = i.endDate
              AND i.startDate >= :today
              AND NOT EXISTS (
                SELECT t FROM TripRecord t WHERE t.itinerary = i
              )
            ORDER BY i.startDate ASC, i.createdAt ASC
            """)
    List<Itinerary> findOngoingDayTripsBySession(
            @Param("sessionUuid") String sessionUuid, @Param("today") LocalDate today);

    /**
     * 같은 세션·같은 날짜에 이미 진행 중인(TripRecord 없는) 당일치기 - 신규 생성 시 중복 판정에 사용.
     * 여러 건이 있을 수 있어 가장 최근 생성분을 기준으로 안내한다.
     */
    @Query("""
            SELECT i FROM Itinerary i
            WHERE i.sessionUuid = :sessionUuid
              AND i.startDate = :startDate
              AND NOT EXISTS (
                SELECT t FROM TripRecord t WHERE t.itinerary = i
              )
            ORDER BY i.createdAt DESC
            """)
    List<Itinerary> findActiveBySessionUuidAndStartDate(
            @Param("sessionUuid") String sessionUuid, @Param("startDate") LocalDate startDate);
}
