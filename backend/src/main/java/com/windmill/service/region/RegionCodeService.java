package com.windmill.service.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RegionSidoGroup;
import com.windmill.dto.RegionSignguOption;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * scripts/generate_region_codes.py로 라이브 생성된 region-codes.json(전국 시군구)을 기동 시 메모리에 로드.
 * legacy areaCd/signguCd는 LDONG 코드에서 파생되므로(RegionCode 참고) 별도 매핑 테이블 없이
 * lDongRegnCd/signguFullCode를 그대로 legacy 코드로도 사용한다.
 */
@Slf4j
@Component
public class RegionCodeService {

    private static final String RESOURCE_PATH = "region-codes.json";

    private Map<String, RegionCode> bySignguFullCode = Map.of();
    private List<RegionSidoGroup> tree = List.of();

    @PostConstruct
    void load() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<RegionCode> all = mapper.readValue(
                    new ClassPathResource(RESOURCE_PATH).getInputStream(),
                    mapper.getTypeFactory().constructCollectionType(List.class, RegionCode.class));

            bySignguFullCode = all.stream()
                    .collect(Collectors.toMap(RegionCode::getSignguFullCode, r -> r, (a, b) -> a, LinkedHashMap::new));

            Map<String, List<RegionCode>> bySido = all.stream()
                    .collect(Collectors.groupingBy(RegionCode::getSidoCode, LinkedHashMap::new, Collectors.toList()));

            tree = bySido.values().stream()
                    .map(group -> RegionSidoGroup.builder()
                            .sidoCode(group.get(0).getSidoCode())
                            .sidoName(group.get(0).getSidoName())
                            .signgus(group.stream()
                                    .sorted(Comparator.comparing(RegionCode::getSignguFullCode))
                                    .map(r -> RegionSignguOption.builder()
                                            .signguFullCode(r.getSignguFullCode())
                                            .signguName(r.getSignguName())
                                            .build())
                                    .collect(Collectors.toList()))
                            .build())
                    .sorted(Comparator.comparing(RegionSidoGroup::getSidoCode))
                    .collect(Collectors.toList());

            log.info("[RegionCodeService] 지역코드 {}개 시군구 로드 완료", bySignguFullCode.size());
        } catch (IOException e) {
            log.error("[RegionCodeService] region-codes.json 로드 실패 - scripts/generate_region_codes.py 실행 필요: {}", e.getMessage());
        }
    }

    public Optional<RegionCode> find(String signguFullCode) {
        return Optional.ofNullable(bySignguFullCode.get(signguFullCode));
    }

    public List<RegionCode> all() {
        return List.copyOf(bySignguFullCode.values());
    }

    public boolean exists(String signguFullCode) {
        return bySignguFullCode.containsKey(signguFullCode);
    }

    public List<RegionSidoGroup> tree() {
        return tree;
    }
}
