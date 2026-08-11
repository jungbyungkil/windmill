package com.windmill.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrowdCongestionEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void categoryBoomBeamIsWarning() {
        assertEquals(CrowdCongestionEvaluator.Level.WARNING,
                CrowdCongestionEvaluator.fromCategory("붐빔"));
        assertEquals(CrowdCongestionEvaluator.Level.DANGER,
                CrowdCongestionEvaluator.fromCategory("매우붐빔"));
        assertEquals(CrowdCongestionEvaluator.Level.NORMAL,
                CrowdCongestionEvaluator.fromCategory("여유"));
    }

    @Test
    void relativePercentThresholds() {
        assertEquals(CrowdCongestionEvaluator.Level.NORMAL,
                CrowdCongestionEvaluator.fromRelativePercent(120.0));
        assertEquals(CrowdCongestionEvaluator.Level.WARNING,
                CrowdCongestionEvaluator.fromRelativePercent(150.0));
        assertEquals(CrowdCongestionEvaluator.Level.DANGER,
                CrowdCongestionEvaluator.fromRelativePercent(200.0));
    }

    @Test
    void evaluatePrefersCategoryOverNumeric() {
        CrowdCongestionEvaluator.Level level = CrowdCongestionEvaluator.evaluate(
                "붐빔", 100.0, 10.0);
        assertEquals(CrowdCongestionEvaluator.Level.WARNING, level);
        assertTrue(level.isTriggered());
        assertFalse(level.isUrgent());
    }

    @Test
    void evaluateUsesRelativeWhenNoCategory() {
        assertEquals(CrowdCongestionEvaluator.Level.DANGER,
                CrowdCongestionEvaluator.evaluate(null, 210.0, 40.0));
    }

    @Test
    void extractCategoryAndNumeric() {
        ObjectNode node = mapper.createObjectNode();
        node.put("cnctrGrade", "붐빔");
        node.put("cnctrRate", 55.0);
        assertEquals("붐빔", CrowdCongestionEvaluator.extractCategory(node));
        assertEquals(55.0, CrowdCongestionEvaluator.extractNumericRate(node));
    }

    @Test
    void relativePercentFromBaseline() {
        Double baseline = CrowdCongestionEvaluator.baselineAverage(List.of(40.0, 50.0, 60.0));
        assertEquals(50.0, baseline);
        assertEquals(160.0, CrowdCongestionEvaluator.relativePercent(80.0, baseline));
    }
}
