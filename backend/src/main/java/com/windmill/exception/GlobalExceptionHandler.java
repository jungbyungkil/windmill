package com.windmill.exception;

import com.windmill.dto.BatchAddItemErrorResponse;
import com.windmill.dto.ClosingTimeInfeasibleResponse;
import com.windmill.dto.DuplicateItineraryResponse;
import com.windmill.dto.TimeSlotConflictResponse;
import jakarta.persistence.EntityNotFoundException;
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
     * 여행 바구니 일괄 추가 중 한 장소에서 실패 - 트랜잭션은 전체 롤백되고, 어떤 장소가 문제였는지
     * failedContentId로, 왜 실패했는지는 원인 예외의 구조화된 필드(대안 시각 등)까지 그대로 내려
     * 단건 추가와 동일한 정보를 준다(2026-09-15 핸드오프 브리프: 지도 다중 선택 → 확인 팝업 일괄
     * 추가, 결정 #9). 상태 코드도 단건 추가와 같은 규칙(TIME_OVERLAP=409, 그 외=422)을 따른다 -
     * 이전엔 항상 422 + message만 있는 즉석 HashMap이라 배치에서만 이 정보를 잃었다(코드 리뷰에서
     * 발견).
     */
    @ExceptionHandler(BatchAddItemException.class)
    public ResponseEntity<BatchAddItemErrorResponse> handleBatchAddItem(BatchAddItemException e) {
        HttpStatus status = e.getCause() instanceof TimeSlotConflictException
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(BatchAddItemErrorResponse.from(e));
    }

    /**
     * 제안이 이미 적용·거절·만료된 뒤 오래된 카드를 눌렀을 때 - 409로 내려 프론트가
     * "제안이 만료됐어요"를 안내하고 카드를 걷어내게 한다.
     */
    @ExceptionHandler(ProposalStaleException.class)
    public ResponseEntity<Map<String, String>> handleProposalStale(ProposalStaleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    /**
     * 삭제됐거나 없는 일정/항목 조회 - 예전엔 전역 처리기가 안 잡아 안전망(500, "일시적인 오류가
     * 발생했어요")으로 새어나가, 프론트가 "진짜 없음(재시도 무의미)"과 "네트워크 순단(재시도해야
     * 함)"을 구분할 수 없었다(2026-09-13 사용자 제보 - 알림 클릭 시 여행 마무리 대신 홈으로 감,
     * 원인 중 하나가 이 구분 불가). 404로 내려 프론트가 즉시 포기하도록 한다.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEntityNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
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
