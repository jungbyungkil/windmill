package com.windmill.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 변경 이력 한 건. {@code Itinerary.changeHistory}에 FIFO(최대 9개)로 쌓인다. 원본은 별도 필드
 * ({@code Itinerary.originalSnapshot})라 FIFO 대상이 아니다.
 *
 * <p>{@code sequence}는 push마다 누적 증가하며 삭제돼도 재사용하지 않는다 - "변경 이력 #7"이
 * FIFO로 밀려나도 다음 항목이 다시 #7을 쓰지 않도록.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanChangeEntry {

    /** 누적 증가 번호(1부터). 표시는 "변경 이력 #{sequence}" */
    private int sequence;

    /** WEATHER | HEAT | CROWD | ROUTE | REVERT | MANUAL */
    private String triggerType;

    /** 사람이 읽는 변경 사유 (예: "폭염경보로 실외 코스 대체", "동선 재계산", "원본으로 되돌림") */
    private String reason;

    /** KST ISO-8601 */
    private String changedAt;

    /** 이번 변경으로 바뀐 장소의 contentId (되돌리기·순수 동선변경이면 null) */
    private String changedPlaceId;

    /** 이번 변경으로 새로 담긴 장소명 (되돌리기·순수 동선변경이면 null) */
    private String changedPlaceName;

    /** 이 변경 직후의 일정 전체 스냅샷 - 되돌리기 대상 */
    private PlanSnapshot snapshot;

    /**
     * 이 변경이 반영된 경로 - MANUAL(직접 조작·CTA 클릭 즉시 적용) | PROPOSAL_ACCEPTED(승인제 제안을
     * 사용자가 확인 후 적용). 과거 항목엔 없을 수 있어(스키마 진화 전) null-safe하게 다룬다
     * (2026-09-15 핸드오프 브리프: 동선 변경 승인제 전환).
     */
    private String source;

    /**
     * 검증에 쓴 공공데이터 근거(혼잡률·영업상태 등) - 승인제 제안에서 넘어온 항목만 채워진다.
     * 심사 시연에서 "공공데이터 실시간 확인" 차별화 포인트를 변경 이력 화면에서도 보여주기 위함.
     */
    @Builder.Default
    private List<Evidence> evidence = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Evidence {
        private String source;
        private String label;
        private String value;
    }
}
