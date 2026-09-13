package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.RouteTangleResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 순서 판정(tangled)은 Haversine 그대로, 화면 표시 수치만 카카오 실제 도로 데이터로 바꾸는
 * 4-인자 detect() 오버로드 검증. 카카오 키가 없는(unconfigured) 클라이언트로는 실제 네트워크
 * 호출 없이 항상 Haversine 폴백으로 떨어져야 한다 - 키 있는 경로(실제 API 호출)는 라이브 네트워크가
 * 필요해 여기서는 검증하지 않는다.
 */
class RouteTangleDetectorTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    private static ItineraryItem coord(long id, String placeName, String mapX, String mapY, int displayOrder) {
        return ItineraryItem.builder()
                .id(id)
                .placeName(placeName)
                .visitDate(TOMORROW)
                .mapX(mapX)
                .mapY(mapY)
                .displayOrder(displayOrder)
                .build();
    }

    /**
     * 일직선 A-B-C-D를 A→D→B→C 순서(displayOrder)로 방문 - 최단(A→B→C→D) 대비 크게 우회해
     * 3-인자 detect()가 tangled=true를 내야 이 테스트가 의미 있음.
     */
    private static List<ItineraryItem> tangledLine() {
        ItineraryItem a = coord(1, "A", "127.00", "37.00", 0);
        ItineraryItem d = coord(4, "D", "127.06", "37.00", 1);
        ItineraryItem b = coord(2, "B", "127.02", "37.00", 2);
        ItineraryItem c = coord(3, "C", "127.04", "37.00", 3);
        return List.of(a, d, b, c);
    }

    @Test
    void detectWithClient_nullClient_sameAsBaseOverload() {
        List<ItineraryItem> items = tangledLine();
        RouteTangleResult base = RouteTangleDetector.detect(items);
        RouteTangleResult withNullClient = RouteTangleDetector.detect(items, null, null, null);

        assertEquals(base.isTangled(), withNullClient.isTangled());
        assertEquals(base.getCurrentDistanceKm(), withNullClient.getCurrentDistanceKm());
        assertEquals(base.getOptimizedDistanceKm(), withNullClient.getOptimizedDistanceKm());
        assertNull(withNullClient.getCurrentDurationMinutes(), "클라이언트 없으면 실제 소요시간은 채우지 않음");
    }

    @Test
    void detectWithClient_unconfiguredClient_fallsBackToHaversineWithoutNetworkCall() {
        List<ItineraryItem> items = tangledLine();
        KakaoDirectionsClient unconfigured = new KakaoDirectionsClient(WebClient.builder(), "");
        RouteTangleResult base = RouteTangleDetector.detect(items);

        RouteTangleResult result = RouteTangleDetector.detect(items, null, null, unconfigured);

        assertTrue(result.isTangled(), "판정 자체는 그대로 Haversine 기준으로 tangled여야 함");
        assertEquals(base.getCurrentDistanceKm(), result.getCurrentDistanceKm());
        assertEquals(base.getOptimizedDistanceKm(), result.getOptimizedDistanceKm());
        assertEquals(base.getMessage(), result.getMessage(), "카카오 데이터가 없으면 기존 Haversine 문구를 그대로 유지");
        assertNull(result.getCurrentDurationMinutes());
    }

    @Test
    void detectWithClient_notTangled_neverTouchesClient() {
        // 이미 짧은 순서면 client가 null이 아니어도(호출 시도조차 없이) base와 동일해야 함
        ItineraryItem a = coord(1, "A", "127.00", "37.00", 0);
        ItineraryItem b = coord(2, "B", "127.01", "37.00", 1);
        ItineraryItem c = coord(3, "C", "127.02", "37.00", 2);
        List<ItineraryItem> shortest = List.of(a, b, c);
        KakaoDirectionsClient unconfigured = new KakaoDirectionsClient(WebClient.builder(), "");

        RouteTangleResult result = RouteTangleDetector.detect(shortest, null, null, unconfigured);

        assertFalse(result.isTangled());
        assertNull(result.getMessage());
    }
}
