package com.windmill.service.recommendation;

import com.windmill.dto.BusinessStatus;
import com.windmill.dto.RelatedCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.IntroFieldCatalog;
import com.windmill.util.TripDayPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
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
    private final SituationalTagService situationalTagService;

    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates) {
        return filter(candidates, null);
    }

    /**
     * @param visitDate 방문일. 미래 날짜면 지금 시각의 영업종료가 아니라 그날 휴무만 표시한다.
     */
    public Mono<List<RelatedCandidate>> filter(List<RelatedCandidate> candidates, LocalDate visitDate) {
        return Flux.fromIterable(candidates)
                .flatMap(c -> enrichAndCheckOpen(c, visitDate), EXTERNAL_CALL_CONCURRENCY)
                .collectList()
                .doOnNext(list -> {
                    long openCount = list.stream().filter(c -> Boolean.TRUE.equals(c.getBusinessOpen())).count();
                    log.info("[Stage2] 상세정보 보강 완료 {}건 (그중 방문일 기준 영업 {}건, 모두 통과)", list.size(), openCount);
                });
    }

    private Mono<RelatedCandidate> enrichAndCheckOpen(RelatedCandidate candidate, LocalDate visitDate) {
        if (candidate.getContentId() == null || candidate.getContentTypeId() == null) {
            candidate.setBusinessOpen(null);
            return Mono.just(candidate);
        }
        return tourAttractionService.getDetail(candidate.getContentId(), candidate.getContentTypeId())
                .flatMap(detail -> Mono.fromCallable(() -> applyDetail(candidate, detail, visitDate))
                        .subscribeOn(Schedulers.boundedElastic()))
                .defaultIfEmpty(openWithoutDetail(candidate))
                .onErrorReturn(openWithoutDetail(candidate));
    }

    private RelatedCandidate applyDetail(RelatedCandidate candidate, TourAttractionDetail detail, LocalDate visitDate) {
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

        var status = resolveStatusForVisit(detail.getIntroFields(), visitDate);
        candidate.setBusinessOpen(status == com.windmill.dto.BusinessStatus.OPEN);
        candidate.setBusinessStatus(status);

        String strollerText = BusinessHoursEvaluator.extractStrollerText(detail.getIntroFields());
        candidate.setStrollerText(strollerText);
        candidate.setStrollerFriendly(BusinessHoursEvaluator.isStrollerFriendly(strollerText));
        candidate.setAccessibleFriendly(BusinessHoursEvaluator.matchesAccessibleKeyword(
                detail.getOverview(), candidate.getCategoryLcls(), candidate.getCategoryMcls(), candidate.getCategoryScls()));
        candidate.setAgeRangeText(BusinessHoursEvaluator.extractAgeRangeText(detail.getIntroFields()));

        String overview = blankToNull(detail.getOverview());
        candidate.setOverview(overview);
        candidate.setDetailFacts(IntroFieldCatalog.toFacts(detail.getIntroFields()));
        candidate.setCat3(detail.getCat3());

        var tags = situationalTagService.ensureInferred(
                candidate.getContentId(), candidate.getContentTypeId(), detail.getCat3(),
                candidate.getPlaceName(), overview);
        candidate.setIndoor(tags.getIndoorYn());
        candidate.setRainSensitivity(tags.getRainSensitivity());
        candidate.setCongestionSensitivity(tags.getCongestionSensitivity());
        candidate.setInferredSource(tags.getInferredSource());
        return candidate;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").trim();
        return t.isBlank() ? null : t.replaceAll("\\s+", " ");
    }

    /**
     * 여행 당일이면 지금 시각의 영업 상태. 미리 계획할 때는 방문일 휴무만 보고,
     * 지금 밤이라 문이 닫혀 있다고 미래 일정을 영업종료로 찍지 않는다.
     */
    static BusinessStatus resolveStatusForVisit(java.util.Map<String, String> introFields, LocalDate visitDate) {
        if (TripDayPolicy.liveConditionsApply(visitDate)) {
            return BusinessHoursEvaluator.currentStatus(introFields);
        }
        if (visitDate != null
                && BusinessHoursEvaluator.isClosedOnRestDate(
                        BusinessHoursEvaluator.extractRestDateText(introFields), visitDate)) {
            return BusinessStatus.CLOSED_DAY;
        }
        return BusinessStatus.OPEN;
    }
    private RelatedCandidate openWithoutDetail(RelatedCandidate candidate) {
        candidate.setBusinessOpen(true);
        candidate.setBusinessStatus(com.windmill.dto.BusinessStatus.OPEN);
        return candidate;
    }
}
