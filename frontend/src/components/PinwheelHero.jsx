const LEVEL_META = {
  NORMAL: { caption: '순항 중', sub: '지금 이 순간, 계획대로 좋아요' },
  WARNING: { caption: '바람이 심상치 않아요', sub: '변수가 하나 감지됐어요' },
  DANGER: { caption: '코스를 바꿀 시간이에요', sub: '변수가 여러 개 겹쳤어요' },
};

const CAUSE_META = {
  weatherTrigger: { icon: '🌧️', label: '비 소식', avoid: 'WEATHER' },
  businessTrigger: { icon: '🚫', label: '영업종료/휴무', avoid: 'BUSINESS' },
  crowdTrigger: { icon: '👥', label: '혼잡', avoid: 'CROWD' },
};

function primaryAvoidHint(trigger) {
  if (!trigger) return undefined;
  if (trigger.weatherTrigger) return 'WEATHER';
  if (trigger.businessTrigger) return 'BUSINESS';
  if (trigger.crowdTrigger) return 'CROWD';
  return undefined;
}

/**
 * 바람따라의 브랜드 얼굴. trigger가 없으면 정지된 장식용 로고 마크로,
 * 있으면 3단계(NORMAL/WARNING/DANGER) 상태 카드로 렌더링된다.
 */
export default function PinwheelHero({ trigger, onRequestAlternatives, loading, onAutoReplace, autoLoading }) {
  const level = trigger?.level || 'NORMAL';
  const meta = LEVEL_META[level];
  const interactive = Boolean(trigger) && level !== 'NORMAL';
  const causes = trigger
    ? Object.entries(CAUSE_META).filter(([key]) => trigger[key])
    : [];

  function handleActivate() {
    if (!interactive || loading) return;
    onRequestAlternatives?.(primaryAvoidHint(trigger));
  }

  return (
    <div className={`pinwheel-hero level-${level.toLowerCase()} ${interactive ? 'clickable' : ''}`}>
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
          <div className="pinwheel-caption">{meta.caption}</div>
          <div className="pinwheel-sub">{meta.sub}</div>

          {trigger.triggerDetails?.length > 0 && (
            <ul className="pinwheel-details">
              {trigger.triggerDetails.map((d, i) => <li key={i}>{d}</li>)}
            </ul>
          )}

          {interactive && (
            <div className="pinwheel-cta-row">
              <button className="btn-pinwheel-cta" onClick={handleActivate} disabled={loading}>
                {loading ? '새 코스 찾는 중...' : '🌬️ 새 코스 추천받기'}
              </button>
              <button
                className="btn-pinwheel-cta secondary"
                onClick={() => onAutoReplace?.(primaryAvoidHint(trigger))}
                disabled={autoLoading}
                title="바로 상위 후보로 자동 교체해요"
              >
                {autoLoading ? '자동 교체 중...' : '⚡ 자동으로 바꾸기'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
