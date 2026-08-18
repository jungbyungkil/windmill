package com.windmill.service.recommendation;

import com.windmill.dto.RelatedCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.service.tourapi.TourAttractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 4단계 검증 로직 - 2단계: 위치/전화/요금/정기휴무 등 상세정보 보강 (detailIntro2의 usetime/restdate 계열 필드).
 * ⚠ 영업중 여부(businessOpen)로 후보를 걸러내지 않는다 - 바람따라의 핵심 기능은 "지금 이 순간 영업중인 곳만
 * 추천"이 아니라, 일정에 담긴 후 영업시간 밖으로 바뀌는 변동사항을 TriggerDetectionService가 감지해 알려주는
 * 것이다. 여기서 걸러버리면 애초에 후보 목록에 오르지도 못해 트리거가 발동할 대상 자체가 사라진다.
 * businessOpen 값 자체는 참고용으로 계속 채워서 넘긴다 - 실제 판정 로직은 BusinessHoursEvaluator(공용
 * 휴리스틱)에 위임하며, TriggerDetectionService와 동일 기준을 쓴다.
 * ⚠ 예전엔 KorServiceClient.detailIntro()만 단독 호출했지만, 위치/전화/요금/정기휴무 카드 표시 요구사항이
 * 추가되며 detailCommon2까지 필요해져 TourAttractionService.getDetail()(공통+소개+이미지 조합, 30분 캐시)로
 * 전환했다 - TriggerDetectionService도 같은 getDetail()을 쓰므로 캐시가 공유되어 API 호출이 오히려 줄어든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage2BusinessHoursFilter {

    /** data.go.kr 요청 제한(429) 방지를 위한 동시 호출 상한 */
    private static final int EXTERNAL_CALL_CONCURRENCY = 4;

    private final TourAttractionService tourAttractionService;

    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates) {
        return Flux.fromIterable(candidates)
                .flatMap(this::enrichAndCheckOpen, EXTERNAL_CALL_CONCURRENCY)
                .collectList()
                .doOnNext(list -> {
                    long openCount = list.stream().filter(c -> Boolean.TRUE.equals(c.getBusinessOpen())).count();
                    log.info("[Stage2] 상세정보 보강 완료 {}건 (그중 지금 영업중 {}건, 모두 통과)", list.size(), openCount);
                });
    }

    private Mono<RelatedCandidate> enrichAndCheckOpen(RelatedCandidate candidate) {
        if (candidate.getContentId() == null || candidate.getContentTypeId() == null) {
            candidate.setBusinessOpen(null);
            return Mono.just(candidate);
        }
        return tourAttractionService.getDetail(candidate.getContentId(), candidate.getContentTypeId())
                .map(detail -> applyDetail(candidate, detail))
                .defaultIfEmpty(openWithoutDetail(candidate))
                .onErrorReturn(openWithoutDetail(candidate));
    }

    private RelatedCandidate applyDetail(RelatedCandidate candidate, TourAttractionDetail detail) {
        candidate.setAddr1(detail.getAddr1());
        candidate.setTel(detail.getTel());
        candidate.setHomepageUrl(detail.getHomepage());
        // 목록에 이미 좌표가 있으면 유지. 상세가 비어 있어도 덮어쓰지 않음.
        if (detail.getMapX() != null && !detail.getMapX().isBlank()) {
            candidate.setMapX(detail.getMapX().trim());
        }
        if (detail.getMapY() != null && !detail.getMapY().isBlank()) {
            candidate.setMapY(detail.getMapY().trim());
        }

        String useFeeText = BusinessHoursEvaluator.extractUseFeeText(detail.getIntroFields());
        candidate.setUseFeeText(useFeeText);
        candidate.setIsFree(BusinessHoursEvaluator.isFree(useFeeText));
        candidate.setEstimatedCostPerPerson(BusinessHoursEvaluator.extractCostAmount(useFeeText));
        candidate.setRestDateText(BusinessHoursEvaluator.extractRestDateText(detail.getIntroFields()));
        candidate.setTel(BusinessHoursEvaluator.extractPhone(detail.getTel(), detail.getIntroFields()));

        String useTimeText = BusinessHoursEvaluator.extractUseTimeText(detail.getIntroFields());
        candidate.setUseTimeText(useTimeText);
        var close = BusinessHoursEvaluator.extractCloseTime(detail.getIntroFields());
        candidate.setCloseTime(BusinessHoursEvaluator.formatHhMm(close));

        var status = BusinessHoursEvaluator.currentStatus(detail.getIntroFields());
        candidate.setBusinessOpen(status == com.windmill.dto.BusinessStatus.OPEN);
        candidate.setBusinessStatus(status);

        String strollerText = BusinessHoursEvaluator.extractStrollerText(detail.getIntroFields());
        candidate.setStrollerText(strollerText);
        candidate.setStrollerFriendly(BusinessHoursEvaluator.isStrollerFriendly(strollerText));
        candidate.setAccessibleFriendly(BusinessHoursEvaluator.matchesAccessibleKeyword(
                detail.getOverview(), candidate.getCategoryLcls(), candidate.getCategoryMcls(), candidate.getCategoryScls()));
        candidate.setAgeRangeText(BusinessHoursEvaluator.extractAgeRangeText(detail.getIntroFields()));
        return candidate;
    }

    /** 상세조회 실패/빈 응답 - 보수적으로 영업중 취급하되 위치/요금 등 부가정보는 비워둔다 */
    private RelatedCandidate openWithoutDetail(RelatedCandidate candidate) {
        candidate.setBusinessOpen(true);
        candidate.setBusinessStatus(com.windmill.dto.BusinessStatus.OPEN);
        return candidate;
    }
}
