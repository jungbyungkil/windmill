package com.windmill.service.itinerary;

import com.windmill.domain.ItineraryItem;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.VisitTiming;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * "동선 최적화" 출발 앵커 결정. 우선순위:
 * 1) 마지막 완료 항목 좌표(권한 불필요) → 2) GPS(완료 항목이 하나도 없을 때만) →
 * 3) 남은 첫 슬롯 항목 좌표(위치 권한 거부·타임아웃·실패 시) → 4) 없음(호출자가 재배치를 건너뛴다).
 */
public final class RouteAnchorResolver {

    private RouteAnchorResolver() {
    }

    public record Anchor(double lon, double lat, boolean fromCompletedItem) {
    }

    public static Anchor resolve(List<ItineraryItem> completedItems, List<ItineraryItem> remainingItems,
                                 Double gpsLon, Double gpsLat) {
        ItineraryItem lastCompleted = completedItems.stream()
                .filter(RouteAnchorResolver::hasCoords)
                .max(Comparator.comparingInt(RouteAnchorResolver::completionOrderKey))
                .orElse(null);
        if (lastCompleted != null) {
            return toAnchor(lastCompleted, true);
        }
        if (completedItems.isEmpty() && gpsLon != null && gpsLat != null) {
            return new Anchor(gpsLon, gpsLat, false);
        }
        ItineraryItem firstRemaining = remainingItems.stream()
                .filter(RouteAnchorResolver::hasCoords)
                .sorted(Comparator.comparing(RouteAnchorResolver::slotMinutes,
                        Comparator.nullsLast(Integer::compareTo)))
                .findFirst()
                .orElse(null);
        return firstRemaining != null ? toAnchor(firstRemaining, false) : null;
    }

    /** 완료 시각 타임스탬프가 없어 체류종료(occupancyEnd)를 대리 지표로 쓴다 - 늦은 슬롯일수록 최근 완료로 본다. */
    private static int completionOrderKey(ItineraryItem item) {
        LocalTime end = VisitTiming.occupancyEnd(item);
        return end != null ? VisitTiming.minutesOf(end) : -1;
    }

    private static Integer slotMinutes(ItineraryItem item) {
        LocalTime t = ClosingTimeGate.parseHhMm(item.getScheduledTime());
        return t == null ? null : VisitTiming.minutesOf(t);
    }

    private static Anchor toAnchor(ItineraryItem item, boolean fromCompleted) {
        return new Anchor(Double.parseDouble(item.getMapX().trim()), Double.parseDouble(item.getMapY().trim()),
                fromCompleted);
    }

    private static boolean hasCoords(ItineraryItem item) {
        return item.getMapX() != null && !item.getMapX().isBlank()
                && item.getMapY() != null && !item.getMapY().isBlank();
    }
}
