package com.windmill.util;

import com.windmill.domain.ItineraryItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutdoorActivityClassifierTest {

    @Test
    void indoorTagWins() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("해변 공원")
                .contentTypeId(12)
                .tags(List.of("#실내"))
                .build();
        assertFalse(OutdoorActivityClassifier.isOutdoor(item));
    }

    @Test
    void natureTagIsOutdoor() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("어딘가")
                .contentTypeId(39)
                .tags(List.of("#자연"))
                .build();
        assertTrue(OutdoorActivityClassifier.isOutdoor(item));
    }

    @Test
    void museumIsIndoor() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("시립박물관")
                .contentTypeId(14)
                .build();
        assertFalse(OutdoorActivityClassifier.isOutdoor(item));
    }

    @Test
    void waterParkIsOutdoor() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("속초 워터파크")
                .contentTypeId(28)
                .build();
        assertTrue(OutdoorActivityClassifier.isOutdoor(item));
    }
}
