package com.windmill.dto;

import com.windmill.exception.BatchAddItemException;
import com.windmill.exception.ClosingTimeInfeasibleException;
import com.windmill.exception.TimeSlotConflictException;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 여행 바구니 일괄 추가 실패 응답 - 어떤 장소(failedContentId)에서, 왜(원인 예외의 구조화된 필드)
 * 실패했는지 그대로 내려준다. 이전엔 GlobalExceptionHandler가 즉석 HashMap에 message만 눌러
 * 담아, 단건 추가(TimeSlotConflictResponse/ClosingTimeInfeasibleResponse)가 주는 대안 시각 제안
 * (suggestedTimes) 등 구조화된 정보를 배치 실패에서만 잃어버렸다(2026-09-15 코드 리뷰에서 발견).
 * 이 파일의 나머지 *Response 클래스들과 같은 static from(e) 컨벤션을 따른다.
 */
@Data
@Builder
public class BatchAddItemErrorResponse {
    private String message;
    private String failedContentId;
    private String failedPlaceName;
    /** TIME_OVERLAP | CLOSING_TIME_INFEASIBLE | null(그 외 원인) */
    private ScheduleConflictCode errorCode;
    private List<String> suggestedTimes;
    /** TimeSlotConflictException 전용 */
    private Long conflictingItemId;
    private String conflictingPlaceName;
    private String conflictingTime;
    /** ClosingTimeInfeasibleException 전용 */
    private String closeTime;
    private String latestArrivalBy;

    public static BatchAddItemErrorResponse from(BatchAddItemException e) {
        BatchAddItemErrorResponseBuilder builder = BatchAddItemErrorResponse.builder()
                .failedContentId(e.getContentId())
                .failedPlaceName(e.getPlaceName());
        Throwable cause = e.getCause();
        if (cause instanceof TimeSlotConflictException tsce) {
            builder.errorCode(ScheduleConflictCode.TIME_OVERLAP)
                    .message(composeMessage(e, tsce.getMessage()))
                    .conflictingItemId(tsce.getConflictingItemId())
                    .conflictingPlaceName(tsce.getConflictingPlaceName())
                    .conflictingTime(tsce.getConflictingTime() == null ? null : tsce.getConflictingTime().toString())
                    .suggestedTimes(tsce.getSuggestedTimes());
        } else if (cause instanceof ClosingTimeInfeasibleException ctie) {
            builder.errorCode(ScheduleConflictCode.CLOSING_TIME_INFEASIBLE)
                    .message(composeMessage(e, ctie.getMessage()))
                    .closeTime(ctie.getCloseTime() == null ? null : ctie.getCloseTime().toString())
                    .latestArrivalBy(ctie.getLatestArrivalBy() == null ? null : ctie.getLatestArrivalBy().toString())
                    .suggestedTimes(ctie.getSuggestedTimes());
        } else {
            builder.message(composeMessage(e, cause == null ? null : cause.getMessage()));
        }
        return builder.build();
    }

    /** cause.getMessage()가 null일 수 있어(예: 의도치 않은 NPE) 문자 그대로 "...null"이 되지 않게 방어. */
    private static String composeMessage(BatchAddItemException e, String causeMessage) {
        String prefix = e.getPlaceName() != null ? "'" + e.getPlaceName() + "' " : "";
        String reason = causeMessage != null && !causeMessage.isBlank() ? causeMessage : "다시 시도해주세요.";
        return prefix + "때문에 추가하지 못했어요. " + reason;
    }
}
