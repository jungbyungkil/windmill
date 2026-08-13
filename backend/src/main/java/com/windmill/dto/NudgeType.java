package com.windmill.dto;

/** 홈 화면 넛지 카드 타입 - priority 오름차순이 노출 우선순위(1이 최상) */
public enum NudgeType {
    WEATHER_RAIN(1),
    CROWD(2),
    WEATHER_HEAT(3),
    CRUISE(4);

    private final int priority;

    NudgeType(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
