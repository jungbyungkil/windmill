package com.windmill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 여행 바구니 바텀시트 - 일괄 공공데이터 검증(2026-09-15 핸드오프 브리프 4.1, 9.5, 10.3).
 * 바텀시트를 열 때(또는 다시 열 때) 1번 호출한다 - 담는 시점엔 개별 호출하지 않아 API 호출을
 * 줄인다(결정 #5).
 */
@Data
public class BatchPlaceCheckRequest {

    @NotEmpty
    private List<@Valid Item> items;

    @Data
    public static class Item {
        @NotBlank
        private String contentId;
        private Integer contentTypeId;
        private String placeName;
        private String category;
        private String cat3;
        private List<String> tags;
        private String restDateText;
        private String closeTime;
        private String useTimeText;
    }
}
