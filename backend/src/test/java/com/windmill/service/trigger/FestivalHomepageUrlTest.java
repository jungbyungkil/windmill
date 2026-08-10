package com.windmill.service.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FestivalHomepageUrlTest {

    @Test
    void extractsHrefFromHtml() {
        String url = FestivalTriggerService.extractHomepageUrl(
                "<a href=\"https://www.example.com/festival\">왕가의 산책</a>");
        assertEquals("https://www.example.com/festival", url);
    }

    @Test
    void extractsPlainHttp() {
        assertEquals("https://tour.example.kr",
                FestivalTriggerService.extractHomepageUrl("https://tour.example.kr"));
    }

    @Test
    void blankReturnsNull() {
        assertNull(FestivalTriggerService.extractHomepageUrl(" "));
        assertNull(FestivalTriggerService.extractHomepageUrl(null));
    }
}
