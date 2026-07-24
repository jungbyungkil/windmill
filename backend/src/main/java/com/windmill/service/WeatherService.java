package com.windmill.service;

import com.windmill.dto.WeatherInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;

@Slf4j
@Service
public class WeatherService {

    @Value("${weather.api.key:}")
    private String apiKey;

    private static final Map<String, double[]> CITY_TEMPS = Map.of(
            "서울", new double[]{22.0, 25.0},
            "부산", new double[]{24.0, 28.0},
            "제주", new double[]{26.0, 30.0},
            "경주", new double[]{23.0, 27.0},
            "강릉", new double[]{21.0, 24.0}
    );

    private static final String[] CONDITIONS = {"맑음", "구름조금", "흐림", "비"};
    private static final String[] ICONS = {"☀️", "🌤️", "☁️", "🌧️"};

    public WeatherInfo getWeather(String location) {
        double[] tempRange = CITY_TEMPS.getOrDefault(location, new double[]{22.0, 26.0});
        Random rand = new Random(location.hashCode());
        int condIdx = rand.nextInt(CONDITIONS.length - 1); // 맑음/구름조금/흐림 위주
        double temp = tempRange[0] + rand.nextDouble() * (tempRange[1] - tempRange[0]);

        String warning = condIdx == 3 ? "우산을 챙기세요. 실내 일정으로 변경을 권장합니다." : null;

        return WeatherInfo.builder()
                .location(location)
                .condition(CONDITIONS[condIdx])
                .temperature(Math.round(temp * 10.0) / 10.0)
                .humidity(50 + rand.nextInt(30))
                .icon(ICONS[condIdx])
                .warning(warning)
                .build();
    }
}
