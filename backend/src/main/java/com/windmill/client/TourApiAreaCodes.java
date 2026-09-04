package com.windmill.client;

/**
 * 법정동 시도 코드({@code lDongRegnCd}) → KorService2 {@code areaCode}.
 * searchFestival2는 lDong 파라미터를 거의 쓰지 않고 areaCode로 지역을 가른다.
 */
public final class TourApiAreaCodes {

    private TourApiAreaCodes() {
    }

    /** 매핑이 없으면 null — 호출부에서 전국 조회로 폴백 */
    public static String fromLDongRegnCd(String lDongRegnCd) {
        if (lDongRegnCd == null || lDongRegnCd.isBlank()) {
            return null;
        }
        return switch (lDongRegnCd) {
            case "11" -> "1";
            case "26" -> "6";
            case "27" -> "4";
            case "28" -> "2";
            case "29" -> "5";
            case "30" -> "3";
            case "31" -> "7";
            case "36" -> "8";
            case "41" -> "31";
            case "42", "51" -> "32";
            case "43" -> "33";
            case "44" -> "34";
            case "45", "52" -> "37";
            case "46" -> "38";
            case "47" -> "35";
            case "48" -> "36";
            case "50" -> "39";
            default -> null;
        };
    }
}
