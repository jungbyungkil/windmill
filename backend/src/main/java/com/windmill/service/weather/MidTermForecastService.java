package com.windmill.service.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.MidFcstClient;
import com.windmill.dto.MidTermForecastResponse;
import com.windmill.dto.RegionCode;
import com.windmill.service.region.RegionCodeService;
import com.windmill.util.MidTermRegionMapper;
import com.windmill.util.TriggerThresholds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MidTermForecastService {

    private final MidFcstClient midFcstClient;
    private final RegionCodeService regionCodeService;

    public Mono<MidTermForecastResponse> bySigngu(String signguFullCode) {
        RegionCode region = regionCodeService.find(signguFullCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + signguFullCode));
        MidTermRegionMapper.MidRegs regs = MidTermRegionMapper.resolve(
                region.getSidoCode(), region.getSidoName(), region.getSignguName());

        if (!midFcstClient.isConfigured()) {
            return Mono.just(empty(regs));
        }

        return Mono.zip(
                        midFcstClient.getMidLandFcst(regs.landRegId()),
                        midFcstClient.getMidTa(regs.taRegId())
                )
                .map(tuple -> assemble(regs, first(tuple.getT1()), first(tuple.getT2())))
                .defaultIfEmpty(empty(regs));
    }

    private MidTermForecastResponse assemble(MidTermRegionMapper.MidRegs regs, JsonNode land, JsonNode ta) {
        LocalDate today = LocalDate.now();
        List<MidTermForecastResponse.DayOutlook> days = new ArrayList<>();
        int rainDays = 0;
        int heatDays = 0;

        for (int offset = 3; offset <= 10; offset++) {
            LocalDate date = today.plusDays(offset);
            String amWf = text(land, offset <= 7 ? "wf" + offset + "Am" : "wf" + offset);
            String pmWf = text(land, offset <= 7 ? "wf" + offset + "Pm" : "wf" + offset);
            Integer amRn = intOrNull(land, offset <= 7 ? "rnSt" + offset + "Am" : "rnSt" + offset);
            Integer pmRn = intOrNull(land, offset <= 7 ? "rnSt" + offset + "Pm" : "rnSt" + offset);
            Integer minT = intOrNull(ta, "taMin" + offset);
            Integer maxT = intOrNull(ta, "taMax" + offset);

            boolean rainRisk = (amRn != null && amRn >= 40) || (pmRn != null && pmRn >= 40)
                    || containsRain(amWf) || containsRain(pmWf);
            boolean heatRisk = maxT != null && maxT >= TriggerThresholds.HEAT_TEMP_THRESHOLD;
            if (rainRisk) rainDays++;
            if (heatRisk) heatDays++;

            days.add(MidTermForecastResponse.DayOutlook.builder()
                    .dayOffset(offset)
                    .date(date.toString())
                    .weekday(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                    .amWeather(amWf)
                    .pmWeather(pmWf)
                    .amRainPercent(amRn)
                    .pmRainPercent(pmRn)
                    .minTemp(minT)
                    .maxTemp(maxT)
                    .rainRisk(rainRisk)
                    .heatRisk(heatRisk)
                    .build());
        }

        List<String> parts = new ArrayList<>();
        parts.add(regs.label() + " 중기(3~10일)");
        if (rainDays > 0) parts.add("비 가능성 " + rainDays + "일");
        if (heatDays > 0) parts.add("더위 주의 " + heatDays + "일");
        if (rainDays == 0 && heatDays == 0) parts.add("대체로 무난한 편");

        return MidTermForecastResponse.builder()
                .regionLabel(regs.label())
                .landRegId(regs.landRegId())
                .taRegId(regs.taRegId())
                .tmFc(MidFcstClient.latestTmFc(java.time.LocalDateTime.now()))
                .summary(String.join(" · ", parts))
                .days(days)
                .build();
    }

    private MidTermForecastResponse empty(MidTermRegionMapper.MidRegs regs) {
        return MidTermForecastResponse.builder()
                .regionLabel(regs.label())
                .landRegId(regs.landRegId())
                .taRegId(regs.taRegId())
                .summary("중기예보를 불러오지 못했어요")
                .days(List.of())
                .build();
    }

    private static JsonNode first(List<JsonNode> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        if (node.path(field).isNumber()) {
            return node.path(field).asInt();
        }
        try {
            return Integer.parseInt(node.path(field).asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsRain(String wf) {
        if (wf == null) return false;
        return wf.contains("비") || wf.contains("소나기") || wf.contains("폭풍");
    }
}
