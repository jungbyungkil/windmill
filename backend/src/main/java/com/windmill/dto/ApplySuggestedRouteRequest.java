package com.windmill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 제안 순서를 사용자가 확인한 뒤에만 오늘 일정에 반영한다.
 */
@Data
public class ApplySuggestedRouteRequest {
    @NotEmpty
    @Valid
    private List<Stop> stops;

    @Data
    public static class Stop {
        @NotNull
        private Long itemId;
        private String scheduledTime;
    }
}
