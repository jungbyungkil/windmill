// 바람따라 넛지 상태 3단계: 🟢 순풍(변수 없음) · 🟡 주의(대응 가능) · 🔴 변경 필요(계획 유지 불가) -
// TriggerLevel(NORMAL/WARNING/DANGER)에 그대로 1:1로 라벨만 입힌다(우선순위·판정 로직은 안 바꿈).
const LEVEL_META = {
  NORMAL: { caption: '순풍 · 계획대로 순항 중이에요' },
  WARNING: { caption: '주의 · 변수가 감지됐어요' },
  DANGER: { caption: '변경 필요 · 지금 코스를 바꿔야 해요' },
};

function primaryAvoidHint(trigger) {
  if (!trigger) return undefined;
  if (trigger.travelTimeTrigger) return 'BUSINESS';
  if (trigger.heatTrigger) return 'HEAT';
  if (trigger.weatherTrigger) return 'WEATHER';
  if (trigger.closedDayTrigger || trigger.hoursEndedTrigger) return 'BUSINESS';
  if (trigger.crowdTrigger) return 'CROWD';
  return undefined;
}

/**
 * 펼친 히어로 CTA. 첫 버튼(primary)은 caption()과 같은 우선순위 = 바람이 말풍선 탭 동작.
 * 동선 관련 변수는 「바람이가 동선 최적화」(현재 순서 다시 짜기)와 「이 순서 어때요?」(근처 다른
 * 장소로 바꾸기, 대안이 무조건 뜸) 두 갈래를 함께 보여 준다.
 */
function resolveCtas(trigger) {
  if (!trigger) return [];
  const ctas = [];
  const add = (cta) => {
    if (!cta) return;
    if (ctas.some((c) => c.kind === cta.kind && c.hint === cta.hint)) return;
    ctas.push(cta);
  };
  const optimize = { kind: 'route', label: '바람이가 동선 최적화' };
  const nearbyAlt = { kind: 'alternatives', hint: 'ROUTE', label: '이 순서 어때요?' };

  // 뭔가 못 가게 된 상황(이동시간 부족·영업종료)은 "다른 장소"가 먼저, 순수 동선 꼬임은 "최적화"가 먼저
  if (trigger.travelTimeTrigger) {
    add(nearbyAlt);
    add(optimize);
  }
  if (trigger.heatTrigger) add({ kind: 'reroute', hint: 'HEAT', label: '실내로 바꾸기' });
  if (trigger.weatherTrigger) add({ kind: 'reroute', hint: 'WEATHER', label: '실내로 바꾸기' });
  if (trigger.crowdTrigger) add({ kind: 'reroute', hint: 'CROWD', label: '한산한 곳으로' });
  if (trigger.routeTangleTrigger) {
    add(optimize);
    add(nearbyAlt);
  }
  if (trigger.hoursEndedTrigger && !trigger.closedDayTrigger) {
    add(nearbyAlt);
    add(optimize);
  }
  if (trigger.closedDayTrigger) {
    add({ kind: 'alternatives', hint: 'BUSINESS', label: '다른 장소 보기' });
  }
  if (ctas.length === 0) {
    add({ kind: 'alternatives', hint: primaryAvoidHint(trigger), label: '다른 장소 보기' });
  }
  return ctas;
}

/**
 * 바람따라의 브랜드 얼굴.
 * 비·폭염으로 야외 일정이 위험하면 빨강(DANGER)으로 바꾸고 실내 대체 일정을 유도한다.
 */
export default function PinwheelHero({
  trigger,
  onRequestAlternatives,
  loading,
  onRerouteSchedule,
  rerouteLoading,
  onOptimizeRoute,
  optimizeLoading,
  /** 액션 클릭 즉시 띄우는 낙관적 성공 스킨: { hint, message } | null */
  optimistic = null,
  /** 액션 실패로 원래 상태로 되돌리는 중 — 0.45초 롤백 애니메이션 */
  rollbackAnimating = false,
  /** 일정 홈: 트리거 로딩 중이거나 순풍이면 한 줄로 접어 장소 목록을 위로 */
  compactWhenIdle = false,
}) {
  const showOptimistic = Boolean(optimistic);
  const level = showOptimistic ? 'NORMAL' : (trigger?.level || 'NORMAL');
  const meta = LEVEL_META[level];
  const interactive = !showOptimistic && Boolean(trigger) && level !== 'NORMAL';
  const heatMode = Boolean(trigger?.heatTrigger);
  const rainMode = Boolean(trigger?.weatherTrigger);
  const crowdMode = Boolean(trigger?.crowdTrigger);
  const weatherAlert = heatMode || rainMode;
  const calm = showOptimistic || (Boolean(trigger) && !interactive) || (compactWhenIdle && !interactive);

  const ctas = interactive ? resolveCtas(trigger) : [];
  const primaryCta = ctas[0] || null;

  function ctaBusy(cta) {
    if (!cta) return false;
    if (cta.kind === 'route') return Boolean(optimizeLoading);
    if (cta.kind === 'reroute') return Boolean(rerouteLoading);
    return Boolean(loading);
  }

  function runCta(cta) {
    if (!cta || ctaBusy(cta)) return;
    if (cta.kind === 'route') {
      onOptimizeRoute?.();
      return;
    }
    if (cta.kind === 'reroute') {
      onRerouteSchedule?.(cta.hint);
      return;
    }
    onRequestAlternatives?.(cta.hint);
  }

  function handleActivate() {
    if (!interactive) return;
    runCta(primaryCta);
  }

  // 변수별 문구는 이제 이 캡션에서 안 보여준다 - 트리거가 있으면 헤드라인 자체를 숨기므로
  // (아래 pinwheel-headline 렌더 조건 참고) 낙관적 성공 스킨과 "변수 없음" 대기 상태만 여기로 들어온다.
  function caption() {
    if (showOptimistic) return optimistic.message;
    return meta.caption;
  }

  return (
    <div
      className={[
        'pinwheel-hero',
        `level-${level.toLowerCase()}`,
        calm ? 'is-calm' : '',
        interactive ? 'clickable' : '',
        showOptimistic ? 'is-optimistic' : '',
        rollbackAnimating ? 'rolling-back' : '',
        !showOptimistic && weatherAlert ? 'weather-alert' : '',
        !showOptimistic && heatMode ? 'heat-alert' : '',
        !showOptimistic && rainMode ? 'rain-alert' : '',
        !showOptimistic && crowdMode ? 'crowd-alert' : '',
      ].filter(Boolean).join(' ')}
    >
      <div className="pinwheel-card-top">
        <div
          className="pinwheel-graphic"
          role={interactive ? 'button' : undefined}
          tabIndex={interactive ? 0 : undefined}
          onClick={handleActivate}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && handleActivate()}
          aria-label={interactive && primaryCta ? `바람개비 상태 - 탭해서 ${primaryCta.label}` : '바람따라 로고'}
        >
          <svg viewBox="0 0 200 200" className="pinwheel-svg" aria-hidden="true">
            <g className="pinwheel-blades">
              {[0, 90, 180, 270].map((deg) => (
                <path
                  key={deg}
                  className="pinwheel-blade"
                  transform={`rotate(${deg} 100 100)`}
                  d="M100,100 C100,60 120,30 155,25 C160,55 145,85 100,100 Z"
                />
              ))}
            </g>
            <circle className="pinwheel-hub" cx="100" cy="100" r="10" />
          </svg>
        </div>

        {/* 변수 카드는 아래 상태 문구를 없앴으니(2026-09-13) 캡션도 같이 걷어낸다 - 낙관적 성공
            스킨("✅ ...")과 대기 상태(compactWhenIdle, 변수 없음)에서만 캡션을 보여준다. */}
        {(showOptimistic || !trigger) && (trigger || compactWhenIdle) && (
          <div className="pinwheel-headline">
            {!calm && <div className="pinwheel-eyebrow">실시간 변수</div>}
            <div className="pinwheel-caption">
              {showOptimistic && <span className="pinwheel-check" aria-hidden="true">✅ </span>}
              {caption()}
            </div>
          </div>
        )}
      </div>

      {/* 캡션(위 pinwheel-headline)과 별개 문구를 여기서 또 보여주면 반복으로 느껴진다는 피드백에
          따라(2026-09-13) 동선 꼬임·비·폭염·혼잡·이동시간 부족·휴무·마감 전 상태 공통으로 서브텍스트·
          원인 라벨·상세 목록·바람이 말풍선을 걷어내고, 액션 버튼만 남겼다. */}
      {interactive && !calm && ctas.length > 0 && (
        <div className="pinwheel-status">
          <div className="pinwheel-cta-row">
            {ctas.map((cta, index) => (
              <button
                key={`${cta.kind}-${cta.hint || 'none'}`}
                type="button"
                className={`btn-pinwheel-cta${index > 0 ? ' secondary' : ''}`}
                onClick={() => runCta(cta)}
                disabled={ctaBusy(cta)}
              >
                {ctaBusy(cta)
                  ? (cta.kind === 'route' ? '동선 최적화 중...' : cta.kind === 'reroute' ? '바꾸는 중...' : '대안 찾는 중...')
                  : cta.label}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
