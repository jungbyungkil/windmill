package com.windmill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 여행 바구니 장소 1건의 검증 결과(2026-09-15 핸드오프 브리프 10.4) - urgent(🔴)면 바텀시트가
 * 배지+사유를 보여주되 확정은 막지 않는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceCheckResult {
    private String contentId;
    /** DANGER면 true - 바텀시트의 🔴 배지 기준 */
    private boolean urgent;
    /** WARNING 이상이면 true(urgent 포함) */
    private boolean warning;
    /** "혼잡" | "폭염" | "우천" | "휴무" | "마감임박" - 최대 3개, 기존 상태 배지 규칙과 동일 */
    private List<String> reasons;
    /** 사람이 읽는 한 줄 사유(첫 번째 트리거 상세) */
    private String detail;
}
