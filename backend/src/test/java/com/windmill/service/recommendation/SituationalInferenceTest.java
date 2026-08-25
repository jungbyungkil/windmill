package com.windmill.service.recommendation;

import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.RainSensitivity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cat3·contentTypeId·overview로 실내/우천/혼잡을 추론한다.
 * 매핑이 없으면 우천 민감(안전한 실패). 레포츠(28)는 초기엔 종목 분기 없이 전부 민감.
 */
class SituationalInferenceTest {

    @Test
    void museumCat3IsIndoorAndRainInsensitive() {
        SituationalInference.Result r = SituationalInference.infer(14, "A02060100", "시립박물관", null);
        assertTrue(r.indoor());
        assertEquals(RainSensitivity.INSENSITIVE, r.rain());
        assertEquals(CongestionSensitivity.INSENSITIVE, r.congestion());
    }

    @Test
    void foodTypeIsIndoorEvenWithoutCat3() {
        SituationalInference.Result r = SituationalInference.infer(39, null, "한식당", null);
        assertTrue(r.indoor());
        assertEquals(RainSensitivity.INSENSITIVE, r.rain());
    }

    @Test
    void tourismWithoutCat3DefaultsToRainSensitive() {
        SituationalInference.Result r = SituationalInference.infer(12, "Z99999999", "알 수 없는 명소", null);
        assertFalse(r.indoor());
        assertEquals(RainSensitivity.SENSITIVE, r.rain());
        assertEquals(CongestionSensitivity.SENSITIVE, r.congestion());
    }

    @Test
    void leportsIsRainSensitiveWithoutSportSplit() {
        SituationalInference.Result r = SituationalInference.infer(28, null, "클라이밍장", null);
        assertFalse(r.indoor());
        assertEquals(RainSensitivity.SENSITIVE, r.rain());
    }

    @Test
    void themeParkIsOutdoorAndCongestionSensitive() {
        SituationalInference.Result r = SituationalInference.infer(12, "A02020600", "테마파크", null);
        assertFalse(r.indoor());
        assertEquals(RainSensitivity.SENSITIVE, r.rain());
        assertEquals(CongestionSensitivity.SENSITIVE, r.congestion());
    }

    @Test
    void overviewIndoorKeywordOverridesOutdoorCat3() {
        SituationalInference.Result r = SituationalInference.infer(
                12, "A01010100", "숲속 전시관", "실내 시설로 우천 시에도 관람할 수 있습니다.");
        assertTrue(r.indoor());
        assertEquals(RainSensitivity.INSENSITIVE, r.rain());
    }

    @Test
    void overviewOutdoorKeywordOverridesIndoorCat3() {
        SituationalInference.Result r = SituationalInference.infer(
                14, "A02060500", "야외조각공원", "야외 조각 전시. 우천 시 취소됩니다.");
        assertFalse(r.indoor());
        assertEquals(RainSensitivity.SENSITIVE, r.rain());
    }

    @Test
    void naturePrefixIsOutdoor() {
        SituationalInference.Result r = SituationalInference.infer(12, "A01010100", "국립공원", null);
        assertFalse(r.indoor());
        assertEquals(RainSensitivity.SENSITIVE, r.rain());
    }
}
