package com.windmill.service.region;

import com.windmill.dto.RegionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * region-codes.json(scripts/generate_region_codes.py로 생성)이 실제 라이브 API로 검증한
 * legacy 코드 파생 공식(legacyAreaCd=lDongRegnCd, legacySignguCd=lDongRegnCd+lDongSignguCd)을
 * 그대로 담고 있는지 확인한다. 서울 종로구/강원 속초 두 지역은 2026-08-03 라이브 호출로 검증됨.
 */
class RegionCodeServiceTest {

    private final RegionCodeService service = new RegionCodeService();

    @BeforeEach
    void load() {
        service.load();
    }

    @Test
    void loadsRegionsFromResource() {
        assertFalse(service.tree().isEmpty(), "region-codes.json이 로드되지 않음 - scripts/generate_region_codes.py 실행 필요");
    }

    @Test
    void jongnoDerivesLegacyCodeFromLdong() {
        RegionCode jongno = service.find("11110").orElseThrow();
        assertEquals("서울특별시", jongno.getSidoName());
        assertEquals("종로구", jongno.getSignguName());
        assertEquals("11", jongno.getLDongRegnCd());
        assertEquals("110", jongno.getLDongSignguCd());
    }

    @Test
    void sokchoDerivesLegacyCodeFromLdong() {
        RegionCode sokcho = service.find("51210").orElseThrow();
        assertEquals("속초시", sokcho.getSignguName());
        assertEquals("51", sokcho.getLDongRegnCd());
        assertEquals("210", sokcho.getLDongSignguCd());
    }

    @Test
    void unknownCodeReturnsEmpty() {
        Optional<RegionCode> result = service.find("99999");
        assertTrue(result.isEmpty());
    }
}
