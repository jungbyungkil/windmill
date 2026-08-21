package com.windmill.exception;

import com.windmill.dto.DuplicateItineraryResponse;
import com.windmill.dto.TimeSlotConflictResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateActiveItineraryException.class)
    public ResponseEntity<DuplicateItineraryResponse> handleDuplicateActiveItinerary(
            DuplicateActiveItineraryException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(DuplicateItineraryResponse.from(e.getExisting()));
    }

    /**
     * 시간대 겹침(TimeConflictGate) - 마감시간 게이트(IllegalArgumentException)와 원인이 다르므로
     * 별도 타입으로 구분해, 프론트가 "마감 임박"이 아닌 "시간 겹침" 문구를 정확히 골라 보여줄 수 있게 한다.
     */
    @ExceptionHandler(TimeSlotConflictException.class)
    public ResponseEntity<TimeSlotConflictResponse> handleTimeSlotConflict(TimeSlotConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(TimeSlotConflictResponse.from(e));
    }

    /**
     * 마감시간 초과·잘못된 요청 등 - 서버 오류(500)가 아니라 사용자가 조치할 수 있는 400으로
     * 내려준다. 예전엔 전역 처리기가 없어 이런 IllegalArgumentException이 그대로 500으로 새면서
     * 프론트에 스프링 기본 에러 바디({"timestamp":...,"status":500,...})가 원문 그대로 노출됐다
     * (message 필드가 없어 windmillApi.js의 request()가 그 JSON 전체를 에러 메시지로 씀).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
