package com.windmill.exception;

/**
 * 제안 승인/거절 시점에 이미 없어졌거나(적용·거절·만료됨) proposalId가 요청과 어긋난 경우 -
 * 오래된 카드를 눌렀을 때 409로 내려 "제안이 만료됐어요" 안내를 띄우기 위함.
 */
public class ProposalStaleException extends RuntimeException {
    public ProposalStaleException(String message) {
        super(message);
    }
}
