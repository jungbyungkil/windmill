package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScheduleItem {
    private String time;
    private String place;
    private String activity;
    private String address;
    private String category;
    private String note;
}
