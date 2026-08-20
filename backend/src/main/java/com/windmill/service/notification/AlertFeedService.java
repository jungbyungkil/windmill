package com.windmill.service.notification;

import com.windmill.dto.AlertEventResponse;
import com.windmill.repository.AlertEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** 알림 피드 화면(GET /api/itineraries/{id}/alert-feed) 조회 전용 - 최신순 N건 */
@Service
@RequiredArgsConstructor
public class AlertFeedService {

    private final AlertEventRepository alertEventRepository;

    public List<AlertEventResponse> list(Long itineraryId, int limit) {
        return alertEventRepository.findByItineraryIdOrderByCreatedAtDesc(itineraryId, PageRequest.of(0, limit))
                .stream()
                .map(AlertEventResponse::from)
                .toList();
    }
}
