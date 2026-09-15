package com.windmill.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자 승인 전까지 일정에 반영되지 않는 자동 변경 제안. {@code Itinerary.pendingProposal}에 세션당
 * 최대 1건만 담긴다 - 새 제안은 우선순위 규칙에 따라 기존 제안을 덮어쓰거나, 같은 trigger면
 * in-place로 갱신한다(2026-09-15 핸드오프 브리프: 동선 변경 승인제 전환).
 *
 * <p>PENDING 상태에서는 {@code itinerary_item}의 슬롯 순서를 절대 수정하지 않는다 - accept가
 * 호출됐을 때만 실제 변경이 반영된다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PendingProposal {

    private String proposalId;

    /** WEATHER | HEAT | CROWD | ROUTE | CLOSED */
    private String trigger;

    /** 사람이 읽는 변경 사유 한 줄 (예: "동선이 꼬였어요. 순서를 정리하면 이동이 줄어요.") */
    private String reason;

    @Builder.Default
    private List<Evidence> evidence = new ArrayList<>();

    @Builder.Default
    private List<PlanSnapshot.Stop> before = new ArrayList<>();

    @Builder.Default
    private List<PlanSnapshot.Stop> after = new ArrayList<>();

    /** 제안 대상 방문일 "yyyy-MM-dd" - accept 시 이 날짜 기준으로 재계산한다 */
    private String date;

    /** KST ISO-8601 */
    private String createdAt;

    /** KST ISO-8601 - 지나면 accept/reject 시 409 */
    private String expiresAt;

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
