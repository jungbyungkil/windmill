package com.windmill.util;

import com.windmill.dto.DetailFact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * detailIntro2 원본 키 → 한글 라벨. contentType별로 필드명이 갈라지므로(음식점 firstmenu, 관광지 parking 등)
 * 전 타입 키를 한 카탈로그에 모아 두고, 값이 있는 것만 노출한다.
 * 카드에 이미 따로 그리는 필드(이용시간·휴무·요금·전화·유모차)는 여기서 빼 중복을 막는다.
 */
public final class IntroFieldCatalog {

    private static final Set<String> SKIP_KEYS = Set.of(
            "contentid", "contenttypeid", "contentid2",
            "usetime", "opentime", "usetimeculture", "usetimefestival", "usetimeleports", "opentimefood",
            "restdate", "restdateculture", "restdatefood", "restdateleports", "restdateshopping",
            "usefee", "usefeeculture", "usefeeleports", "usefeefestival",
            "infocenter", "infocenterculture", "infocenterfood", "infocenterleports",
            "infocenterlodging", "infocentershopping", "infocenterfestival",
            "chkbabycarriage", "chkbabycarriageculture", "chkbabycarriageleports", "chkbabycarriageshopping",
            "homepage", "overview");

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        // 음식점(39)
        LABELS.put("firstmenu", "대표메뉴");
        LABELS.put("treatmenu", "취급메뉴");
        LABELS.put("reservationfood", "예약안내");
        LABELS.put("parkingfood", "주차");
        LABELS.put("seat", "좌석수");
        LABELS.put("packing", "포장");
        LABELS.put("chkcreditcardfood", "신용카드");
        LABELS.put("opendatefood", "개업일");
        LABELS.put("scalefood", "규모");
        LABELS.put("discountinfofood", "할인정보");
        LABELS.put("kidsfacility", "어린이놀이방");
        LABELS.put("smoking", "금연/흡연");
        LABELS.put("lcnsno", "인허가번호");
        // 관광지(12)
        LABELS.put("parking", "주차");
        LABELS.put("opendate", "개장일");
        LABELS.put("expguide", "체험안내");
        LABELS.put("expagerange", "체험가능연령");
        LABELS.put("useseason", "이용시기");
        LABELS.put("accomcount", "수용인원");
        LABELS.put("chkpet", "반려동물");
        LABELS.put("chkcreditcard", "신용카드");
        LABELS.put("heritage1", "세계문화유산");
        LABELS.put("heritage2", "세계자연유산");
        LABELS.put("heritage3", "세계기록유산");
        // 문화시설(14)
        LABELS.put("parkingculture", "주차");
        LABELS.put("parkingfee", "주차요금");
        LABELS.put("scale", "규모");
        LABELS.put("spendtime", "관람소요시간");
        LABELS.put("discountinfo", "할인정보");
        LABELS.put("accomcountculture", "수용인원");
        LABELS.put("chkpetculture", "반려동물");
        LABELS.put("chkcreditcardculture", "신용카드");
        // 레포츠(28)
        LABELS.put("parkingleports", "주차");
        LABELS.put("parkingfeeleports", "주차요금");
        LABELS.put("openperiod", "개장기간");
        LABELS.put("reservation", "예약안내");
        LABELS.put("scaleleports", "규모");
        LABELS.put("accomcountleports", "수용인원");
        LABELS.put("expagerangeleports", "체험가능연령");
        LABELS.put("chkpetleports", "반려동물");
        LABELS.put("chkcreditcardleports", "신용카드");
        // 쇼핑(38)
        LABELS.put("parkingshopping", "주차");
        LABELS.put("opentime", "영업시간");
        LABELS.put("opendateshopping", "개장일");
        LABELS.put("saleitem", "판매품목");
        LABELS.put("saleitemcost", "판매품목가격");
        LABELS.put("shopguide", "매장안내");
        LABELS.put("fairday", "장서는 날");
        LABELS.put("restroom", "화장실");
        LABELS.put("culturecenter", "문화센터");
        LABELS.put("scaleshopping", "규모");
        LABELS.put("chkcreditcardshopping", "신용카드");
        LABELS.put("chkpetshopping", "반려동물");
        // 축제(15)
        LABELS.put("eventstartdate", "시작일");
        LABELS.put("eventenddate", "종료일");
        LABELS.put("eventplace", "행사장소");
        LABELS.put("playtime", "공연시간");
        LABELS.put("program", "프로그램");
        LABELS.put("subevent", "부대행사");
        LABELS.put("placeinfo", "장소 안내");
        LABELS.put("sponsor1", "주최");
        LABELS.put("sponsor1tel", "주최 전화");
        LABELS.put("sponsor2", "주관");
        LABELS.put("sponsor2tel", "주관 전화");
        LABELS.put("agelimit", "관람가능연령");
        LABELS.put("bookingplace", "예매처");
        LABELS.put("spendtimefestival", "관람소요시간");
        LABELS.put("eventhomepage", "행사 홈페이지");
        LABELS.put("discountinfofestival", "할인정보");
        // 숙박(32)
        LABELS.put("checkintime", "입실");
        LABELS.put("checkouttime", "퇴실");
        LABELS.put("parkinglodging", "주차");
        LABELS.put("reservationlodging", "예약안내");
        LABELS.put("reservationurl", "예약 URL");
        LABELS.put("roomcount", "객실수");
        LABELS.put("roomtype", "객실유형");
        LABELS.put("foodplace", "식음료장");
        LABELS.put("subfacility", "부대시설");
        LABELS.put("barbecue", "바베큐");
        LABELS.put("sauna", "사우나");
        LABELS.put("publicbath", "공용욕실");
        LABELS.put("refundregulation", "환불규정");
        LABELS.put("chkcooking", "취사");
        LABELS.put("pickup", "픽업");
        LABELS.put("hanok", "한옥");
        LABELS.put("goodstay", "굿스테이");
        LABELS.put("scalelodging", "규모");
        LABELS.put("accomcountlodging", "수용인원");
        LABELS.put("fitness", "피트니스");
        LABELS.put("beverage", "식음료");
        LABELS.put("karaoke", "노래방");
        LABELS.put("sports", "스포츠시설");
        LABELS.put("seminar", "세미나실");
        LABELS.put("beauty", "뷰티시설");
        LABELS.put("bicycle", "자전거대여");
        LABELS.put("campfire", "캠프파이어");
        LABELS.put("publicpc", "공용PC");
        // 여행코스(25)
        LABELS.put("distance", "코스 거리");
        LABELS.put("taketime", "소요시간");
        LABELS.put("schedule", "일정");
        LABELS.put("theme", "테마");
    }

    private IntroFieldCatalog() {
    }

    /**
     * introFields에서 값이 있는 항목만 라벨과 함께 돌려준다. 전용 UI가 있는 키는 제외.
     * 카탈로그에 없는 키는 원본 키 대신 "기타"로 붙이지 않고 키 자체를 라벨로 쓴다(신규 필드 누락 방지).
     */
    public static List<DetailFact> toFacts(Map<String, String> introFields) {
        if (introFields == null || introFields.isEmpty()) {
            return List.of();
        }
        List<DetailFact> known = new ArrayList<>();
        List<DetailFact> unknown = new ArrayList<>();
        for (Map.Entry<String, String> e : introFields.entrySet()) {
            String key = e.getKey();
            if (key == null) {
                continue;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (SKIP_KEYS.contains(normalized)) {
                continue;
            }
            String value = strip(e.getValue());
            if (value == null) {
                continue;
            }
            String label = LABELS.get(normalized);
            DetailFact fact = DetailFact.builder()
                    .key(normalized)
                    .label(label != null ? label : key)
                    .value(value)
                    .build();
            if (label != null) {
                known.add(fact);
            } else {
                unknown.add(fact);
            }
        }
        known.addAll(unknown);
        return known;
    }

    private static String strip(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").trim();
        if (t.isBlank() || t.equalsIgnoreCase("없음") || t.equalsIgnoreCase("null")) {
            return null;
        }
        return t.replaceAll("\\s+", " ");
    }
}
