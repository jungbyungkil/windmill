package com.windmill.exception;

/**
 * 여행 바구니 일괄 추가 중 특정 장소에서 실패 - 트랜잭션 전체가 롤백되기 전에 "어떤 장소가
 * 문제였는지"를 프론트에 알려주기 위해 원인 예외를 감싼다(2026-09-15 핸드오프 브리프 10.5:
 * 실패 시 해당 항목을 바텀시트에서 하이라이트).
 */
public class BatchAddItemException extends RuntimeException {

    private final String contentId;
    private final String placeName;

    public BatchAddItemException(String contentId, String placeName, Throwable cause) {
        super(cause.getMessage(), cause);
        this.contentId = contentId;
        this.placeName = placeName;
    }

    public String getContentId() {
        return contentId;
    }

    public String getPlaceName() {
        return placeName;
    }
}
