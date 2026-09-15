package com.windmill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPlaceCheckResponse {
    private List<PlaceCheckResult> results;
    private int urgentCount;
    /** KST ISO-8601 - "공공데이터 실시간 검증 완료" 문구 옆에 노출 */
    private String checkedAt;
}
