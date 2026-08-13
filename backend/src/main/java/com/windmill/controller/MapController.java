package com.windmill.controller;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 카카오 길찾기 서버 프록시 — REST 키 노출 방지.
 */
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MapController {

    private final KakaoDirectionsClient kakaoDirectionsClient;

    @PostMapping("/route")
    public Mono<ResponseEntity<MapRouteResponse>> route(@RequestBody MapRouteRequest request) {
        return kakaoDirectionsClient.route(
                        request == null ? null : request.getPoints(),
                        request == null ? null : request.getMode())
                .map(ResponseEntity::ok);
    }
}
