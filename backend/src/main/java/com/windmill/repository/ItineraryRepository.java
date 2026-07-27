package com.windmill.repository;

import com.windmill.domain.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
    List<Itinerary> findBySessionUuid(String sessionUuid);
}
