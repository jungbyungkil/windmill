package com.windmill.service.recommendation;

import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.RainSensitivity;

import java.util.Locale;
import java.util.Set;

/**
 * cat3·contentTypeId·overview로 실내/우천/혼잡 민감도를 규칙 추론한다.
 *
 * <p>cat3는 전국 코드를 다 채우지 않고, 실제 검색/일정에 자주 나오는 상위 분류(자연·문화시설·음식·레포츠·쇼핑)
 * 를 1차로 커버한다. 롱테일은 contentTypeId 기본값으로 흡수한다.
 *
 * <p>레포츠(28)는 초기에 세분화하지 않고 전부 우천 민감. 매핑이 없으면 우천 민감(안전한 실패).
 */
public final class SituationalInference {

    /** 실내로 보는 소분류 — 문화시설·음식·실내 쇼핑/온천 등 빈도 상위 */
    private static final Set<String> INDOOR_CAT3 = Set.of(
            "A02060100", // 박물관
            "A02060200", // 기념관
            "A02060300", // 전시관
            "A02060500", // 미술관
            "A02060600", // 공연장
            "A02060700", // 문화원
            "A02060800", // 외국문화원
            "A02060900", // 도서관
            "A02061000", // 대형실내경기장? keep if present
            "A02020300", // 온천/욕장
            "A05020100", // 한식
            "A05020200", // 서양식
            "A05020300", // 일식
            "A05020400", // 중식
            "A05020500", // 아시아식
            "A05020600", // 패밀리레스토랑
            "A05020700", // 이색음식점
            "A05020800", // 채식
            "A05020900", // 카페
            "A05021000", // 클럽
            "A04010300", // 백화점
            "A04010400", // 면세점
            "A04010600", // 전문매장
            "A04010700"  // 공방
    );

    private static final Set<String> INDOOR_CAT3_PREFIX = Set.of(
            "A0206", // 문화시설
            "A0502"  // 음식점
    );

    private static final Set<String> OUTDOOR_CAT3_PREFIX = Set.of(
            "A01",   // 자연
            "A0302", // 육상레포츠
            "A0303", // 수상레포츠
            "A0304", // 항공레포츠
            "A0201"  // 역사관광지(고궁·사찰·유적)
    );

    private static final Set<String> OUTDOOR_CAT3 = Set.of(
            "A02020600", // 테마파크
            "A02030600", // 이색거리
            "A04010100", // 5일장
            "A04010200", // 상설시장
            "A03021700", // 야영
            "A03021800", // 등산
            "A03022700"  // 트레킹
    );

    private static final Set<String> CONGESTION_SENSITIVE_CAT3 = Set.of(
            "A04010100",
            "A04010200",
            "A02020600",
            "A02010100", // 고궁
            "A02010800"  // 사찰
    );

    private SituationalInference() {
    }

    public record Result(boolean indoor, RainSensitivity rain, CongestionSensitivity congestion) {
    }

    public static Result infer(Integer contentTypeId, String cat3, String placeName, String overview) {
        Boolean indoorFromText = indoorFromOverview(overview, placeName);
        Boolean indoorFromCat = indoorFromCat3(cat3);
        boolean indoor;
        if (indoorFromText != null) {
            indoor = indoorFromText;
        } else if (indoorFromCat != null) {
            indoor = indoorFromCat;
        } else {
            indoor = indoorFromContentType(contentTypeId);
        }

        RainSensitivity rainFromText = rainFromOverview(overview);
        RainSensitivity rain = rainFromText != null
                ? rainFromText
                : (indoor ? RainSensitivity.INSENSITIVE : RainSensitivity.SENSITIVE);

        CongestionSensitivity congestion = congestionFromCat3(cat3);
        if (congestion == null) {
            congestion = congestionFromContentType(contentTypeId);
        }
        return new Result(indoor, rain, congestion);
    }

    static Boolean indoorFromCat3(String cat3) {
        if (cat3 == null || cat3.isBlank()) {
            return null;
        }
        String c = cat3.trim();
        if (INDOOR_CAT3.contains(c)) {
            return true;
        }
        if (OUTDOOR_CAT3.contains(c)) {
            return false;
        }
        for (String prefix : INDOOR_CAT3_PREFIX) {
            if (c.startsWith(prefix)) {
                return true;
            }
        }
        for (String prefix : OUTDOOR_CAT3_PREFIX) {
            if (c.startsWith(prefix)) {
                return false;
            }
        }
        return null;
    }

    private static boolean indoorFromContentType(Integer contentTypeId) {
        if (contentTypeId == null) {
            return false;
        }
        return contentTypeId == 14 || contentTypeId == 39 || contentTypeId == 32;
    }

    private static Boolean indoorFromOverview(String overview, String placeName) {
        String text = ((overview == null ? "" : overview) + " " + (placeName == null ? "" : placeName))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        boolean indoorHit = containsAny(text, "실내", "우천 시에도", "우천시에도", "우천 시 이용", "실내전시", "실내 전시");
        boolean outdoorHit = containsAny(text, "실외", "야외", "노천", "야외전시", "야외 전시", "우천 시 취소", "우천시 취소");
        if (indoorHit && !outdoorHit) {
            return true;
        }
        if (outdoorHit && !indoorHit) {
            return false;
        }
        return null;
    }

    private static RainSensitivity rainFromOverview(String overview) {
        if (overview == null || overview.isBlank()) {
            return null;
        }
        String text = overview.toLowerCase(Locale.ROOT);
        if (containsAny(text, "우천 시 취소", "우천시 취소", "우천 시 운영하지", "비 오는 날 휴무")) {
            return RainSensitivity.SENSITIVE;
        }
        if (containsAny(text, "우천 시에도", "우천시에도", "우천 시 이용 가능", "실내 시설")) {
            return RainSensitivity.INSENSITIVE;
        }
        return null;
    }

    private static CongestionSensitivity congestionFromCat3(String cat3) {
        if (cat3 != null && CONGESTION_SENSITIVE_CAT3.contains(cat3.trim())) {
            return CongestionSensitivity.SENSITIVE;
        }
        return null;
    }

    private static CongestionSensitivity congestionFromContentType(Integer contentTypeId) {
        if (contentTypeId != null && (contentTypeId == 14 || contentTypeId == 39 || contentTypeId == 32)) {
            return CongestionSensitivity.INSENSITIVE;
        }
        return CongestionSensitivity.SENSITIVE;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
