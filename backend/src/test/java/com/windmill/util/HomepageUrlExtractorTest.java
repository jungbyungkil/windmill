package com.windmill.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HomepageUrlExtractorTest {

    @Test
    void extractsHrefFromHtml() {
        String url = HomepageUrlExtractor.extract(
                "<a href=\"https://www.example.com/festival\">왕가의 산책</a>");
        assertEquals("https://www.example.com/festival", url);
    }

    @Test
    void extractsPlainHttp() {
        assertEquals("https://tour.example.kr",
                HomepageUrlExtractor.extract("https://tour.example.kr"));
    }

    @Test
    void blankReturnsNull() {
        assertNull(HomepageUrlExtractor.extract(" "));
        assertNull(HomepageUrlExtractor.extract(null));
    }
}
