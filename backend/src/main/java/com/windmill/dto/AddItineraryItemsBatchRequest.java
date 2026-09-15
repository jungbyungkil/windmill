package com.windmill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 여행 바구니 확정 - 일괄 추가 요청(2026-09-15 핸드오프 브리프: 지도 다중 선택 → 확인 팝업 일괄 추가).
 *
 * <p>브리프 4.2절은 {@code { dayIndex, placeIds[] }} 형태를 제안하지만, 실제 단건 추가
 * ({@link AddItineraryItemRequest})가 TourAPI 조회 없이 클라이언트가 보낸 스냅샷 필드를 그대로
 * 쓰는 구조라 placeId만으로는 추가할 수 없다(서버에 별도 상세조회 단계가 없음). 당일치기 전용
 * 앱이라 dayIndex도 의미가 없어 뺐다 - 항상 itinerary.startDate 하루뿐이다.
 */
@Data
public class AddItineraryItemsBatchRequest {

    @NotEmpty
    private List<@Valid AddItineraryItemRequest> items;
}
