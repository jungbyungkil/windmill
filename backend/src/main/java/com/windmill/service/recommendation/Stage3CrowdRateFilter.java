package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.CrowdRateClient;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.util.CrowdCongestionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 4단계 검증 로직 - 3단계: 집중률(혼잡도) 조회 (TatsCnctrRateService).
 * 관광지명(tAtsNm) 기준으로 오늘자 집중률을 붙여 두고, 들어온 순서(Stage1 관련도)는 유지한다.
 * 한산한 곳 우선은 혼잡 회피(CROWD) 요청에서만 적용하고, 스마트 동선은 인기(집중률↑) 명소를
 * 오전에 배치한다. 조회 실패/데이터 없음은 crowdRate=null로 두고 제외하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage3CrowdRateFilter {

    /** data.go.kr 요청 제한(429) 방지를 위한 동시 호출 상한 */
    private static final int EXTERNAL_CALL_CONCURRENCY = 4;

    private final CrowdRateClient crowdRateClient;

    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates, RegionCode region) {
        return Flux.fromIterable(candidates)
                .flatMapSequential(c -> attachCrowdRate(c, region), EXTERNAL_CALL_CONCURRENCY)
                .collectList()
                .doOnNext(list -> log.info("[Stage3] 집중률 조회 완료, 순서 유지 ({}건)", list.size()));
    }

    private Mono<RelatedCandidate> attachCrowdRate(RelatedCandidate candidate, RegionCode region) {
        // legacy areaCd/signguCd는 LDONG에서 파생됨: areaCd=lDongRegnCd, signguCd=signguFullCode
        return crowdRateClient.crowdRateList(region.getLDongRegnCd(), region.getSignguFullCode(), candidate.getPlaceName(), 1, 1)
                .map(items -> {
                    if (!items.isEmpty()) {
                        JsonNode first = items.get(0);
                        Double rate = CrowdCongestionEvaluator.extractNumericRate(first);
                        if (rate != null) {
                            candidate.setCrowdRate(rate);
                        }
                    }
                    return candidate;
                })
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }
}
