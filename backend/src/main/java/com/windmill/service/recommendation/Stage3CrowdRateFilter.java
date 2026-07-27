package com.windmill.service.recommendation;

import com.windmill.client.CrowdRateClient;
import com.windmill.dto.RelatedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

/**
 * 4단계 검증 로직 - 3단계: 집중률(혼잡도) 필터링 (TatsCnctrRateService).
 * 관광지명(tAtsNm) 기준으로 오늘자 집중률을 조회해 여유율(=100-집중률) 높은 순으로 정렬한다.
 * 집중률 조회 실패/데이터 없음인 후보는 제외하지 않고 crowdRate=null로 유지해 리스트 뒤쪽에 배치한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage3CrowdRateFilter {

    /** data.go.kr 요청 제한(429) 방지를 위한 동시 호출 상한 */
    private static final int EXTERNAL_CALL_CONCURRENCY = 4;

    private final CrowdRateClient crowdRateClient;

    @Value("${windmill.region.sokcho.legacy-area-cd}")
    private String areaCd;

    @Value("${windmill.region.sokcho.legacy-signgu-cd}")
    private String signguCd;

    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates) {
        return Flux.fromIterable(candidates)
                .flatMap(this::attachCrowdRate, EXTERNAL_CALL_CONCURRENCY)
                .collectList()
                .map(list -> {
                    list.sort(Comparator.comparing(RelatedCandidate::getCrowdRate,
                            Comparator.nullsLast(Comparator.naturalOrder())));
                    log.info("[Stage3] 집중률 조회 완료, 여유율 높은 순 정렬 ({}건)", list.size());
                    return list;
                });
    }

    private Mono<RelatedCandidate> attachCrowdRate(RelatedCandidate candidate) {
        return crowdRateClient.crowdRateList(areaCd, signguCd, candidate.getPlaceName(), 1, 1)
                .map(items -> {
                    if (!items.isEmpty()) {
                        candidate.setCrowdRate(items.get(0).path("cnctrRate").asDouble());
                    }
                    return candidate;
                })
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }
}
