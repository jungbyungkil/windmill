package com.windmill.util;

/**
 * 시군구 → 기상청 중기육상(regId) / 중기기온(regId) 매핑.
 * 육상은 광역 구역, 기온은 대표 도시 코드를 쓴다.
 */
public final class MidTermRegionMapper {

    private MidTermRegionMapper() {
    }

    public record MidRegs(String landRegId, String taRegId, String label) {
    }

    public static MidRegs resolve(String sidoCode, String sidoName, String signguName) {
        String sido = sidoCode == null ? "" : sidoCode;
        String sidoPart = sidoName == null ? "" : sidoName;
        String signguPart = signguName == null ? "" : signguName;
        String name = sidoPart + " " + signguPart;

        // 강원: 영동/영서 구분
        if ("42".equals(sido) || "51".equals(sido) || name.contains("강원")) {
            if (isYeongdong(signguName)) {
                return new MidRegs("11D20000", "11D20501", "강원영동");
            }
            return new MidRegs("11D10000", "11D10301", "강원영서");
        }

        return switch (sido) {
            case "11" -> new MidRegs("11B00000", "11B10101", "서울");
            case "28" -> new MidRegs("11B00000", "11B20201", "인천");
            case "41" -> new MidRegs("11B00000", "11B20601", "경기");
            case "30", "36" -> new MidRegs("11C20000", "11C20401", "대전·세종");
            case "44" -> new MidRegs("11C20000", "11C20301", "충남");
            case "43" -> new MidRegs("11C10000", "11C10301", "충북");
            case "29" -> new MidRegs("11F20000", "11F20501", "광주");
            case "46" -> new MidRegs("11F20000", "11F20401", "전남");
            case "45", "52" -> new MidRegs("11F10000", "11F10201", "전북");
            case "27" -> new MidRegs("11H10000", "11H10701", "대구");
            case "47" -> new MidRegs("11H10000", "11H10501", "경북");
            case "26" -> new MidRegs("11H20000", "11H20201", "부산");
            case "31" -> new MidRegs("11H20000", "11H20101", "울산");
            case "48" -> new MidRegs("11H20000", "11H20301", "경남");
            case "50" -> new MidRegs("11G00000", "11G00201", "제주");
            default -> new MidRegs("11B00000", "11B10101", "수도권(기본)");
        };
    }

    private static boolean isYeongdong(String signguName) {
        if (signguName == null) return false;
        return signguName.contains("속초")
                || signguName.contains("고성")
                || signguName.contains("양양")
                || signguName.contains("강릉")
                || signguName.contains("동해")
                || signguName.contains("삼척")
                || signguName.contains("태백");
    }
}
