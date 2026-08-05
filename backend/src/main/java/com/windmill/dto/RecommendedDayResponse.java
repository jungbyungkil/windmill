package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class RecommendedDayResponse {
    private int dayNo;
    /** startDate 쿼리파라미터가 있을 때만 채워짐 - 없으면 null */
    private LocalDate date;
    private List<RecommendedItemResponse> items;
}
