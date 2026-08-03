package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** GET /api/regions 응답 - 프론트 시/도->시/군/구 캐스케이딩 드롭다운 데이터 소스 */
@Data
@Builder
public class RegionSidoGroup {
    private String sidoCode;
    private String sidoName;
    private List<RegionSignguOption> signgus;
}
