package com.windmill.repository;

import com.windmill.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {
    Optional<PushSubscription> findByFcmToken(String fcmToken);

    List<PushSubscription> findBySessionUuid(String sessionUuid);

    List<PushSubscription> findByItineraryId(Long itineraryId);
}
