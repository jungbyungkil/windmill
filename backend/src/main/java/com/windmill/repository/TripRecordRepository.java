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

    /** 첫 화면 피드 - 좋아요 → 클릭 → 최근 완료 순 상위 5건 */
    List<TripRecord> findTop5ByOrderByLikeCountDescClickCountDescCompletedAtDesc();
}
