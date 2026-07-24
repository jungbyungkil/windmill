package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WeatherInfo {
    private String location;
    private String condition;
    private double temperature;
    private int humidity;
    private String icon;
    private String warning;
}
