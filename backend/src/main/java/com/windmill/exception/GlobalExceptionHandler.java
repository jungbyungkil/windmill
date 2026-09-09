package com.windmill.exception;

import com.windmill.dto.ClosingTimeInfeasibleResponse;
import com.windmill.dto.DuplicateItineraryResponse;
import com.windmill.dto.TimeSlotConflictResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
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
     * 영업 마감으로 해당 시각 배치 불가. 409(TIME_OVERLAP)와 구분하려고 422를 쓴다 —
     * 프론트가 errorCode로 모달 문구를 고른다.
     */
    @ExceptionHandler(ClosingTimeInfeasibleException.class)
    public ResponseEntity<ClosingTimeInfeasibleResponse> handleClosingTimeInfeasible(
            ClosingTimeInfeasibleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ClosingTimeInfeasibleResponse.from(e));
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

    /**
     * 안전망 - 위에서 안 잡힌 모든 예외. 예전엔 전역 처리기가 이 케이스를 안 덮어
     * DB 스키마 오류(SQLGrammarException 등)·NPE 등이 그대로 스프링 기본 500 바디
     * ({"timestamp":...,"status":500,...})로 프론트에 노출됐다(windmillApi.js의 request()가
     * message 필드가 없어 그 JSON 전체를 에러 메시지로 씀).
     *
     * <p>{@link ErrorResponse}(표준 MVC 예외 - 400 검증 실패, 404, 405, 415 등)는 상태코드를
     * 그대로 보존하고 {@code message} 필드만 붙인다(4xx→500 회귀 방지). 그 외 진짜 예상 밖
     * 예외만 500으로 내린다.
     *
     * <p>예외 캡처(Sentry)는 이 핸들러보다 먼저 도는 {@code SentryExceptionResolver}
     * (highest precedence)가 담당하므로 여기서 별도 캡처하지 않는다(중복 리포팅 방지 -
     * application.yml의 sentry-logback 미추가 사유와 동일).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            String message = status.is4xxClientError()
                    ? "요청을 처리할 수 없어요. 입력을 확인해 주세요."
                    : "일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요.";
            return ResponseEntity.status(status).body(Map.of("message", message));
        }
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요."));
    }
}
