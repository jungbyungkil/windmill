package com.windmill.client;

import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KakaoDirectionsClientTest {

    @Test
    void straightFallbackConnectsPointsInOrder() {
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(127.0).lat(37.0).build(),
                MapRouteRequest.MapPoint.builder().lon(127.05).lat(37.0).build()
        );
        MapRouteResponse res = KakaoDirectionsClient.straightFallback(points, "직선");
        assertFalse(res.isRoadBased());
        assertEquals(2, res.getPath().size());
        assertEquals(37.0, res.getPath().get(0).getLat());
        assertEquals(127.05, res.getPath().get(1).getLng());
        assertTrue(res.getDistanceMeters() != null && res.getDistanceMeters() > 0);
    }

    @Test
    void etaListFromOrigin_unconfigured_fallsBackToHaversineForEveryPoint() {
        // 카카오 키 미설정 - 다중 목적지 길찾기를 아예 호출하지 않고 Haversine 추정으로만 채운다.
        KakaoDirectionsClient client = new KakaoDirectionsClient(WebClient.builder(), "");
        MapRouteRequest.MapPoint origin = MapRouteRequest.MapPoint.builder().lon(127.0).lat(37.0).build();
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(127.01).lat(37.0).build(),
                MapRouteRequest.MapPoint.builder().lon(127.05).lat(37.0).build());

        List<KakaoDirectionsClient.DestinationEta> etas = client.etaListFromOrigin(origin, points);

        assertEquals(2, etas.size());
        for (KakaoDirectionsClient.DestinationEta eta : etas) {
            assertFalse(eta.ok(), "미설정 상태는 추정치라 ok=false여야 함");
            assertTrue(eta.minutes() >= 1);
        }
        // 더 먼 지점이 더 오래 걸려야 함(단조성)
        assertTrue(etas.get(1).minutes() >= etas.get(0).minutes());
    }

    @Test
    void minutesFromOrigin_unconfigured_matchesEtaListMinutes() {
        KakaoDirectionsClient client = new KakaoDirectionsClient(WebClient.builder(), "");
        MapRouteRequest.MapPoint origin = MapRouteRequest.MapPoint.builder().lon(127.0).lat(37.0).build();
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(127.02).lat(37.0).build(),
                MapRouteRequest.MapPoint.builder().lon(127.03).lat(37.0).build());

        int[] minutes = client.minutesFromOrigin(origin, points);
        List<KakaoDirectionsClient.DestinationEta> etas = client.etaListFromOrigin(origin, points);

        assertEquals(2, minutes.length);
        for (int i = 0; i < minutes.length; i++) {
            assertEquals(etas.get(i).minutes(), minutes[i]);
        }
    }

    @Test
    void minutesFromOrigin_nullOrigin_returnsZeroFilledArrayOfSameLength() {
        // 기존 계약 유지: origin이 null이면 호출자는 여전히 points와 같은 길이의 배열을 받아야 함
        KakaoDirectionsClient client = new KakaoDirectionsClient(WebClient.builder(), "");
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(127.0).lat(37.0).build(),
                MapRouteRequest.MapPoint.builder().lon(127.01).lat(37.0).build());

        int[] minutes = client.minutesFromOrigin(null, points);

        assertEquals(2, minutes.length);
        assertEquals(0, minutes[0]);
        assertEquals(0, minutes[1]);
    }
}
