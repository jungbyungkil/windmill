package com.windmill.exception;

import com.windmill.domain.Itinerary;
import lombok.Getter;

/** 같은 세션·같은 날짜에 이미 진행 중인 당일치기가 있을 때 신규 생성을 막기 위해 던진다. */
@Getter
public class DuplicateActiveItineraryException extends RuntimeException {

    private final Itinerary existing;

    public DuplicateActiveItineraryException(Itinerary existing) {
        super("이미 해당 날짜에 진행 중인 일정이 있습니다: " + existing.getId());
        this.existing = existing;
    }
}
