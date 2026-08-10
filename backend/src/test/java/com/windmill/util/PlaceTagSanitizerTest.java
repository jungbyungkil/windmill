package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceTagSanitizerTest {

    @Test
    void stripsFoodTagFromExhibitionHall() {
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                List.of("#맛집"), 14, "X:PORT 엑스포트 인천국제공항 디지털전시체험관", null);
        assertFalse(tags.contains("#맛집"));
        assertTrue(tags.contains("#실내"));
    }

    @Test
    void stripsFoodTagFromStation() {
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                List.of("#맛집", "#실내"), 14, "하이커 스테이션", null);
        assertFalse(tags.contains("#맛집"));
        assertTrue(tags.contains("#실내"));
    }

    @Test
    void keepsFoodTagForRestaurantType() {
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                List.of("#맛집"), 39, "300도씨해물갈비", "음식점");
        assertTrue(tags.contains("#맛집"));
    }

    @Test
    void filtersToRequestedTagsOnly() {
        List<String> tags = PlaceTagSanitizer.sanitize(
                List.of("#맛집", "#실내", "#자연"),
                14,
                "디지털전시체험관",
                null,
                List.of("#실내", "#자연", "#아이동반"));
        assertFalse(tags.contains("#맛집"));
        assertTrue(tags.contains("#실내"));
    }
}
