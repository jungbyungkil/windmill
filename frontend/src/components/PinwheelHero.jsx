import BaramiBubble from './BaramiBubble';
import { baramiCommentFromTrigger } from '../utils/statusLevel';

// 바람따라 넛지 상태 3단계: 🟢 순풍(변수 없음) · 🟡 주의(대응 가능) · 🔴 변경 필요(계획 유지 불가) -
// TriggerLevel(NORMAL/WARNING/DANGER)에 그대로 1:1로 라벨만 입힌다(우선순위·판정 로직은 안 바꿈).
const LEVEL_META = {
  NORMAL: { caption: '순풍 · 계획대로 순항 중이에요', sub: '날씨·혼잡·동선이 바뀌면 여기서 미리 알려드려요' },
  WARNING: { caption: '주의 · 변수가 감지됐어요', sub: '일정을 조금 바꾸면 더 편해질 수 있어요' },
  DANGER: { caption: '변경 필요 · 지금 코스를 바꿔야 해요', sub: '이대로면 계획을 지키기 어려워요, 대안을 확인하세요' },
};

const CAUSE_META = {
  heatTrigger: { label: '폭염', avoid: 'HEAT' },
  weatherTrigger: { label: '비 소식', avoid: 'WEATHER' },
  closedDayTrigger: { label: '휴무', avoid: 'BUSINESS' },
  hoursEndedTrigger: { label: '마감', avoid: 'BUSINESS' },
  crowdTrigger: { label: '혼잡', avoid: 'CROWD' },
  routeTangleTrigger: { label: '동선 꼬임', avoid: null },
  travelTimeTrigger: { label: '이동시간 부족', avoid: 'BUSINESS' },
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

function hasRouteCta(trigger) {
  return Boolean(
    trigger?.travelTimeTrigger
    || trigger?.routeTangleTrigger
    || (trigger?.hoursEndedTrigger && !trigger?.closedDayTrigger),
  );
}

/**
 * 펼친 히어로 CTA. 첫 버튼은 caption()과 같은 우선순위.
 * 휴무+동선 꼬임처럼 원인이 겹치면 버튼을 같이 보여 준다 — 예전엔 휴무가
 * 「다른 장소 보기」만 남기고 「동선 다시」를 가렸다.
 */
function resolveCtas(trigger) {
  if (!trigger) return [];
  const ctas = [];
  const add = (cta) => {
    if (!cta) return;
    if (ctas.some((c) => c.kind === cta.kind && c.hint === cta.hint)) return;
    ctas.push(cta);
  };
  if (trigger.travelTimeTrigger) add({ kind: 'route', label: '동선 다시' });
  if (trigger.heatTrigger) add({ kind: 'reroute', hint: 'HEAT', label: '실내로 바꾸기' });
  if (trigger.weatherTrigger) add({ kind: 'reroute', hint: 'WEATHER', label: '실내로 바꾸기' });
  if (trigger.crowdTrigger) add({ kind: 'reroute', hint: 'CROWD', label: '한산한 곳으로' });
  if (hasRouteCta(trigger)) add({ kind: 'route', label: '동선 다시' });
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
  /** 일정 홈: 트리거 로딩 중이거나 순풍이면 한 줄로 접어 장소 목록을 위로 */
  compactWhenIdle = false,
}) {
  const level = trigger?.level || 'NORMAL';
  const meta = LEVEL_META[level];
  const interactive = Boolean(trigger) && level !== 'NORMAL';
  const causes = trigger
    ? Object.entries(CAUSE_META).filter(([key]) => trigger[key])
    : [];
  const heatMode = Boolean(trigger?.heatTrigger);
  const rainMode = Boolean(trigger?.weatherTrigger);
  const crowdMode = Boolean(trigger?.crowdTrigger);
  const tangleMode = Boolean(trigger?.routeTangleTrigger);
  const travelTimeMode = Boolean(trigger?.travelTimeTrigger);
  const weatherAlert = heatMode || rainMode;
  const calm = (Boolean(trigger) && !interactive) || (compactWhenIdle && !interactive);

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

  function caption() {
    if (travelTimeMode) return '이동시간 부족 · 다음 장소 마감이 임박했어요';
    if (heatMode) return '폭염 소식 · 실내로 바꾸세요';
    if (rainMode) return '비 소식 · 실내로 바꾸세요';
    if (crowdMode) return '혼잡 · 한산한 곳으로 바꾸세요';
    if (tangleMode) return '동선이 꼬였어요 · 자동 재배치';
    return meta.caption;
  }

  function sub() {
    if (travelTimeMode) {
      const detail = trigger?.triggerDetails?.find((d) => d.includes('까지 약'));
      return detail || '지금 위치에서 다음 장소까지 이동시간이 부족해요. 대안을 확인해보세요.';
    }
    if (heatMode) return '야외 일정이 있어요. 실내 활동으로 전환을 권해요.';
    if (rainMode) return '야외 일정이 있어요. 비에 맞는 실내 코스를 추천할게요.';
    if (crowdMode) return '붐비는 장소가 있어요. 한산한 일정으로 바꿀 수 있어요.';
    if (tangleMode) {
      return trigger?.routeTangle?.message
        || '방문 순서를 다시 잡아 이동 거리를 줄일 수 있어요.';
    }
    return meta.sub;
  }

  return (
    <div
      className={[
        'pinwheel-hero',
        `level-${level.toLowerCase()}`,
        calm ? 'is-calm' : '',
        interactive ? 'clickable' : '',
        weatherAlert ? 'weather-alert' : '',
        heatMode ? 'heat-alert' : '',
        rainMode ? 'rain-alert' : '',
        crowdMode ? 'crowd-alert' : '',
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

        {(trigger || compactWhenIdle) && (
          <div className="pinwheel-headline">
            {!calm && <div className="pinwheel-eyebrow">실시간 변수</div>}
            <div className="pinwheel-caption">{caption()}</div>
          </div>
        )}
      </div>

      {trigger && !calm && (
        <div className="pinwheel-status">
          <p className="pinwheel-sub">{sub()}</p>

          {causes.length > 0 && (
            <div className="pinwheel-cause-labels">
              {causes.map(([key, c]) => (
                <span key={key} className="cause-label">{c.label}</span>
              ))}
            </div>
          )}

          {trigger.triggerDetails?.length > 0 && (
            <ul className="pinwheel-details">
              {trigger.triggerDetails.map((d, i) => <li key={i}>{d}</li>)}
            </ul>
          )}

          {interactive && ctas.length > 0 && (
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
                    ? (cta.kind === 'route' ? '동선 다시 짜는 중...' : cta.kind === 'reroute' ? '바꾸는 중...' : '찾는 중...')
                    : cta.label}
                </button>
              ))}
            </div>
          )}

          <BaramiBubble comment={baramiCommentFromTrigger(trigger)} compact />
        </div>
      )}
    </div>
  );
}
