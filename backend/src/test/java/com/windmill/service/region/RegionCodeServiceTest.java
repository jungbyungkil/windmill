package com.windmill.service.region;

import com.windmill.dto.RegionCode;
import com.windmill.dto.RegionSidoGroup;
import com.windmill.dto.RegionSignguOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    /**
     * 2026-09-11 사용자 제보 - 시/도가 행정구역 코드순(서울=11이 최상단)이 아니라 가나다순이어야
     * 한다. 코드순이면 "강원특별자치도"(51)가 "경기도"(41)보다 뒤에 오지만, 가나다순이면 앞에 온다.
     */
    @Test
    void sidoGroupsAreSortedAlphabetically() {
        List<String> sidoNames = service.tree().stream().map(RegionSidoGroup::getSidoName).toList();
        List<String> sorted = sidoNames.stream().sorted().toList();
        assertEquals(sorted, sidoNames);
    }

    /**
     * 2026-09-11 사용자 제보 - "종로구"가 코드값(11110)이 낮다는 이유만으로 서울 목록 맨 위에 오던
     * 문제. 가나다순이면 "강남구"가 "종로구"보다 앞에 와야 한다.
     */
    @Test
    void seoulSignguOptionsAreSortedAlphabetically() {
        RegionSidoGroup seoul = service.tree().stream()
                .filter(g -> "서울특별시".equals(g.getSidoName()))
                .findFirst().orElseThrow();
        List<String> names = seoul.getSigngus().stream().map(RegionSignguOption::getSignguName).toList();
        List<String> sorted = names.stream().sorted().toList();
        assertEquals(sorted, names);
        assertTrue(names.indexOf("강남구") < names.indexOf("종로구"),
                "가나다순이면 강남구가 종로구보다 앞이어야 함, got " + names);
    }
}
