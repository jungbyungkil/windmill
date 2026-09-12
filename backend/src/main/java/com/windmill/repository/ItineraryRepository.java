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

    /**
     * 알림 대상 일정 - 오늘(KST)이 여행일이고, 아직 "여행 마무리"(TripRecord)를 하지 않은 모든 세션의
     * 당일치기. 세션 필터가 없다 - NotificationSchedulerService는 특정 사용자가 아니라 전체를 스캔하는
     * 백그라운드 잡이기 때문. findOngoingDayTripsBySession과 동일한 NOT EXISTS 패턴 재사용.
     */
    @Query("""
            SELECT i FROM Itinerary i
            WHERE i.startDate = :today
              AND NOT EXISTS (
                SELECT t FROM TripRecord t WHERE t.itinerary = i
              )
            """)
    List<Itinerary> findActiveTodayForNotification(@Param("today") LocalDate today);

    /**
     * 같은 세션·같은 지역의 다른 당일치기 일정들(자기 자신 제외) - 2박3일처럼 여러 날을 당일치기
     * 여러 건으로 나눠 쓰는 경우, 다른 날 일정에 이미 담긴 장소를 추천에서 제외하기 위한 조회
     * (2026-09-12 사용자 요청: "당일치기 1"·"당일치기 2"가 같은 속초시인데 서로 동일한 추천을 줌).
     * 완료 여부(TripRecord)는 가리지 않는다 - 전날 일정이 이미 "다녀왔음" 처리됐어도 그 장소는
     * 다음날 추천에서 여전히 빠져야 하기 때문. 날짜가 몇 달씩 떨어진 재방문까지 섞이지 않도록 하는
     * 근접일 필터는 호출부(SiblingTripExclusionResolver)에서 Java 레벨로 건다.
     */
    List<Itinerary> findBySessionUuidAndSignguFullCodeAndIdNot(
            String sessionUuid, String signguFullCode, Long id);
}
