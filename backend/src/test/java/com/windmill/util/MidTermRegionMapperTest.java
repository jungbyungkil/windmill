package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidTermRegionMapperTest {

    @Test
    void mapsSokchoToYeongdong() {
        var regs = MidTermRegionMapper.resolve("51", "강원특별자치도", "속초시");
        assertEquals("11D20000", regs.landRegId());
        assertEquals("11D20501", regs.taRegId());
        assertTrue(regs.label().contains("영동"));
    }

    @Test
    void mapsSeoul() {
        var regs = MidTermRegionMapper.resolve("11", "서울특별시", "종로구");
        assertEquals("11B00000", regs.landRegId());
        assertEquals("11B10101", regs.taRegId());
    }
}

class MidFcstTmFcTest {

    @Test
    void picksMorningOrEveningSlot() {
        String morning = com.windmill.client.MidFcstClient.latestTmFc(LocalDateTime.of(2026, 8, 7, 10, 0));
        assertTrue(morning.endsWith("0600"));
        String evening = com.windmill.client.MidFcstClient.latestTmFc(LocalDateTime.of(2026, 8, 7, 20, 0));
        assertTrue(evening.endsWith("1800"));
    }
}
