import BaramiBubble from './BaramiBubble';
import { baramiCommentFromTrigger } from '../utils/statusLevel';

const LEVEL_META = {
  NORMAL: { caption: '변수 없음 · 순항 중', sub: '날씨·혼잡·동선이 바뀌면 여기서 미리 알려드려요' },
  WARNING: { caption: '변수 감지 · 미리 알려드려요', sub: '일정을 조금 바꾸면 더 편해질 수 있어요' },
  DANGER: { caption: '변수 주의 · 대안이 필요해요', sub: '지금 코스를 바꾸면 낭비를 줄일 수 있어요' },
};

const CAUSE_META = {
  heatTrigger: { icon: '🌡️', label: '폭염', avoid: 'HEAT' },
  weatherTrigger: { icon: '🌧️', label: '비 소식', avoid: 'WEATHER' },
  closedDayTrigger: { icon: '🚫', label: '휴무', avoid: 'BUSINESS' },
  hoursEndedTrigger: { icon: '🕐', label: '영업종료', avoid: 'BUSINESS' },
  crowdTrigger: { icon: '👥', label: '혼잡', avoid: 'CROWD' },
  routeTangleTrigger: { icon: '🔀', label: '동선 꼬임', avoid: null },
};

function primaryAvoidHint(trigger) {
  if (!trigger) return undefined;
  if (trigger.heatTrigger) return 'HEAT';
  if (trigger.weatherTrigger) return 'WEATHER';
  if (trigger.closedDayTrigger || trigger.hoursEndedTrigger) return 'BUSINESS';
  if (trigger.crowdTrigger) return 'CROWD';
  return undefined;
}

/**
 * 바람따라의 브랜드 얼굴.
 * 비·폭염으로 야외 일정이 위험하면 빨강(DANGER)으로 바꾸고 실내 대체 일정을 유도한다.
 */
export default function PinwheelHero({
  trigger,
  onRequestAlternatives,
  loading,
  onAutoReplace,
  autoLoading,
  onRerouteSchedule,
  rerouteLoading,
  onOptimizeRoute,
  optimizeLoading,
}) {
  const level = trigger?.level || 'NORMAL';
  const meta = LEVEL_META[level];
  const interactive = Boolean(trigger) && level !== 'NORMAL';
  const causes = trigger
    ? Object.entries(CAUSE_META).filter(([key]) => trigger[key])
    : [];
  const heatMode = Boolean(trigger?.heatTrigger);
  const rainMode = Boolean(trigger?.weatherTrigger);
  const tangleMode = Boolean(trigger?.routeTangleTrigger);
  const weatherAlert = heatMode || rainMode;

  function handleActivate() {
    if (!interactive || loading) return;
    if (tangleMode && !weatherAlert && onOptimizeRoute) {
      onOptimizeRoute();
      return;
    }
    onRequestAlternatives?.(primaryAvoidHint(trigger));
  }

  function caption() {
    if (heatMode) return '폭염 소식 · 실내로 바꾸세요';
    if (rainMode) return '비 소식 · 실내로 바꾸세요';
    if (tangleMode) return '동선이 꼬였어요 · 자동 재배치';
    return meta.caption;
  }

  function sub() {
    if (heatMode) return '야외 일정이 있어요. 실내 활동으로 전환을 권해요.';
    if (rainMode) return '야외 일정이 있어요. 비에 맞는 실내 코스를 추천할게요.';
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
        interactive ? 'clickable' : '',
        weatherAlert ? 'weather-alert' : '',
        heatMode ? 'heat-alert' : '',
        rainMode ? 'rain-alert' : '',
      ].filter(Boolean).join(' ')}
    >
      <div
        className="pinwheel-graphic"
        role={interactive ? 'button' : undefined}
        tabIndex={interactive ? 0 : undefined}
        onClick={handleActivate}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && handleActivate()}
        aria-label={interactive ? '바람개비 상태 - 탭해서 새 코스 추천받기' : '바람따라 로고'}
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
        {causes.length > 0 && (
          <div className="pinwheel-causes">
            {causes.map(([key, c]) => (
              <span key={key} className="cause-chip" title={c.label}>{c.icon}</span>
            ))}
          </div>
        )}
      </div>

      {trigger && (
        <div className="pinwheel-status">
          <div className="pinwheel-eyebrow">실시간 변수</div>
          <div className="pinwheel-caption">{caption()}</div>
          <div className="pinwheel-sub">{sub()}</div>

          {causes.length > 0 && (
            <div className="pinwheel-cause-labels">
              {causes.map(([key, c]) => (
                <span key={key} className="cause-label">{c.icon} {c.label}</span>
              ))}
            </div>
          )}

          {trigger.triggerDetails?.length > 0 && (
            <ul className="pinwheel-details">
              {trigger.triggerDetails.map((d, i) => <li key={i}>{d}</li>)}
            </ul>
          )}

          {interactive && (
            <div className="pinwheel-cta-row">
              {tangleMode && onOptimizeRoute && (
                <button
                  className="btn-pinwheel-cta"
                  onClick={() => onOptimizeRoute()}
                  disabled={optimizeLoading}
                >
                  {optimizeLoading ? '동선 재계산 중...' : '🔀 동선 재계산'}
                </button>
              )}
              {weatherAlert && onRerouteSchedule && (
                <button
                  className="btn-pinwheel-cta"
                  onClick={() => onRerouteSchedule?.(primaryAvoidHint(trigger))}
                  disabled={rerouteLoading}
                >
                  {rerouteLoading ? '대안 짜는 중...' : '🏠 대안 일정 추천받기'}
                </button>
              )}
              {!tangleMode && (
                <button
                  className={`btn-pinwheel-cta ${weatherAlert ? 'secondary' : ''}`}
                  onClick={handleActivate}
                  disabled={loading}
                >
                  {loading ? '대안 찾는 중...' : weatherAlert ? '대안 후보만 보기' : '🌬️ 대안 코스 추천받기'}
                </button>
              )}
              {!tangleMode && (
                <button
                  className="btn-pinwheel-cta secondary"
                  onClick={() => onAutoReplace?.(primaryAvoidHint(trigger))}
                  disabled={autoLoading}
                  title="영향 받은 첫 장소를 바로 교체해요"
                >
                  {autoLoading ? '교체 중...' : '⚡ 한 곳만 바꾸기'}
                </button>
              )}
            </div>
          )}

          {!interactive && level === 'NORMAL' && (
            <p className="pinwheel-hint">대응한 일정은 기록으로 남아 다른 여행자에게도 참고가 돼요.</p>
          )}

          <BaramiBubble comment={baramiCommentFromTrigger(trigger)} compact />
        </div>
      )}
    </div>
  );
}
