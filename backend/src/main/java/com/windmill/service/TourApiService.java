package com.windmill.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TourApiService {

    @Value("${tourapi.key:}")
    private String apiKey;

    private static final Map<String, List<Attraction>> MOCK_ATTRACTIONS = Map.of(
            "서울", List.of(
                    new Attraction("경복궁", "종로구 사직로 161", "문화유산", 4.7),
                    new Attraction("남산서울타워", "용산구 남산공원길 105", "전망대", 4.5),
                    new Attraction("북촌한옥마을", "종로구 계동길 37", "문화마을", 4.4),
                    new Attraction("인사동", "종로구 인사동길", "쇼핑/문화", 4.3),
                    new Attraction("광장시장", "종로구 창경궁로 88", "전통시장", 4.6)
            ),
            "부산", List.of(
                    new Attraction("해운대 해수욕장", "해운대구 해운대해변로 264", "해변", 4.8),
                    new Attraction("감천문화마을", "사하구 감내2로 203", "문화마을", 4.6),
                    new Attraction("자갈치시장", "중구 자갈치해안로 52", "전통시장", 4.5),
                    new Attraction("용두산공원", "중구 용두산길 37-55", "공원", 4.2)
            ),
            "제주", List.of(
                    new Attraction("성산일출봉", "서귀포시 성산읍 일출로 284-12", "자연", 4.9),
                    new Attraction("한라산", "제주시 한라산로 646", "산/자연", 4.8),
                    new Attraction("협재해수욕장", "제주시 한림읍 협재리", "해변", 4.7),
                    new Attraction("천지연폭포", "서귀포시 천지동 667-7", "자연", 4.5)
            ),
            "경주", List.of(
                    new Attraction("불국사", "경주시 불국로 385", "문화유산", 4.8),
                    new Attraction("석굴암", "경주시 불국로 873-243", "문화유산", 4.7),
                    new Attraction("첨성대", "경주시 첨성로 169-1", "문화유산", 4.4),
                    new Attraction("동궁과 월지", "경주시 원화로 102", "문화유산", 4.6)
            )
    );

    public List<Attraction> getAttractions(String region) {
        return MOCK_ATTRACTIONS.getOrDefault(region,
                List.of(new Attraction(region + " 대표 관광지", region, "관광", 4.0)));
    }

    @Data
    public static class Attraction {
        private final String name;
        private final String address;
        private final String category;
        private final double rating;
    }
}
