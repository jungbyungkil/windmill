package com.windmill.controller;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import com.windmill.dto.NearbyPlaceResponse;
import com.windmill.service.tourapi.LocationBasedSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 카카오 길찾기 서버 프록시 — REST 키 노출 방지.
 * 위치기반 장소 검색(locationBasedList2)도 같은 지도 API 하위에 둔다.
 */
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MapController {

    private final KakaoDirectionsClient kakaoDirectionsClient;
    private final LocationBasedSearchService locationBasedSearchService;

    @PostMapping("/route")
    public Mono<ResponseEntity<MapRouteResponse>> route(@RequestBody MapRouteRequest request) {
        return kakaoDirectionsClient.route(
                        request == null ? null : request.getPoints(),
                        request == null ? null : request.getMode())
                .map(ResponseEntity::ok);
    }

    /**
     * 현재 지도 중심 기준 주변 장소. 프론트의 "이 지역 재검색"만 호출한다(지도 이동 자동 트리거 금지).
     * contentTypeId=39 이면 음식점만.
     */
    @GetMapping("/nearby")
    public Mono<ResponseEntity<List<NearbyPlaceResponse>>> nearby(
            @RequestParam double mapX,
            @RequestParam double mapY,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Integer contentTypeId,
            @RequestParam(required = false) Integer pageNo,
            @RequestParam(required = false) Integer numOfRows) {
        return locationBasedSearchService.search(mapX, mapY, radius, contentTypeId, pageNo, numOfRows)
                .map(ResponseEntity::ok);
    }
}
