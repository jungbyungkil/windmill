package com.windmill.service.recommendation;

import com.windmill.client.KorServiceClient;
import com.windmill.dto.RelatedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 4단계 검증 로직 - 2단계: 영업시간 필터링 (detailIntro2의 usetime/restdate 계열 필드).
 * 실제 판정 로직은 BusinessHoursEvaluator(공용 휴리스틱)에 위임 - TriggerDetectionService와 동일 기준 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage2BusinessHoursFilter {

    /** data.go.kr 요청 제한(429) 방지를 위한 동시 호출 상한 */
    private static final int EXTERNAL_CALL_CONCURRENCY = 4;

    private final KorServiceClient korServiceClient;

    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates) {
        return Flux.fromIterable(candidates)
                .flatMap(c -> checkOpen(c).map(open -> {
                    c.setBusinessOpen(open);
                    return c;
                }), EXTERNAL_CALL_CONCURRENCY)
                .filter(c -> Boolean.TRUE.equals(c.getBusinessOpen()))
                .collectList()
                .doOnNext(list -> log.info("[Stage2] 영업중 후보 {}건 / {}건 중", list.size(), candidates.size()));
    }

    private Mono<Boolean> checkOpen(RelatedCandidate candidate) {
        if (candidate.getContentId() == null || candidate.getContentTypeId() == null) {
            return Mono.just(false);
        }
        return korServiceClient.detailIntro(candidate.getContentId(), candidate.getContentTypeId())
                .map(BusinessHoursEvaluator::isCurrentlyOpen)
                .defaultIfEmpty(true)
                .onErrorReturn(true);
    }
}
