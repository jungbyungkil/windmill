package com.windmill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * region-codes.json 한 행 - scripts/generate_region_codes.py로 라이브 생성된 시군구별 지역코드 매핑.
 * legacyAreaCd/legacySignguCd는 별도 필드 없이 lDongRegnCd/signguFullCode로 대체 가능:
 *   legacyAreaCd = lDongRegnCd, legacySignguCd = signguFullCode
 * ⚠ lDongRegnCd/lDongSignguCd는 getter가 getLDongRegnCd()가 되어 Jackson이 "LDongRegnCd"로
 *   역캡라이즈할 수 있어(첫 두 글자 대문자 조합 케이스) @JsonProperty로 JSON 키를 고정한다.
 * ⚠ Jackson이 region-codes.json을 역직렬화하려면 기본 생성자가 필요 - @Builder만으로는
 *   생성되지 않으므로 @NoArgsConstructor/@AllArgsConstructor를 명시한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionCode {
    private String sidoCode;
    private String sidoName;
    private String signguFullCode;
    private String signguName;
    @JsonProperty("lDongRegnCd")
    private String lDongRegnCd;
    @JsonProperty("lDongSignguCd")
    private String lDongSignguCd;
    private String weatherNx;
    private String weatherNy;
}
