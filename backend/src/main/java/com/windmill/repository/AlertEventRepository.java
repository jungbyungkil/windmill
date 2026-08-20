package com.windmill.repository;

import com.windmill.domain.AlertEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {
    List<AlertEvent> findByItineraryIdOrderByCreatedAtDesc(Long itineraryId, Pageable pageable);
}
