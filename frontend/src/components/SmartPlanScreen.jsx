import { useEffect, useState } from 'react';

/**
 * 바람따라 핵심 화면: TourAPI + 혼잡↓ + 날씨 + 동선 최적화 스마트 일정.
 * 여행 생성 직후 가장 먼저 노출된다.
 */
export default function SmartPlanScreen({
  onGenerate,
  onConfirm,
  onBrowseCategories,
  onSkip,
}) {
  const [plan, setPlan] = useState(null);
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState(null);
  const [checked, setChecked] = useState({});

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    onGenerate()
      .then((result) => {
        if (cancelled) return;
        setPlan(result);
        const stops = result?.stops || [];
        setChecked(Object.fromEntries(stops.map((s) => [s.contentId, true])));
      })
      .catch((e) => {
        if (!cancelled) setError(e.message || '스마트 일정을 만들지 못했어요');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function toggle(contentId) {
    setChecked((prev) => ({ ...prev, [contentId]: !prev[contentId] }));
  }

  async function handleConfirm() {
    if (!plan?.stops) return;
    setConfirming(true);
    try {
      await onConfirm(plan.stops.filter((s) => checked[s.contentId]));
    } finally {
      setConfirming(false);
    }
  }

  const selectedCount = plan?.stops?.filter((s) => checked[s.contentId]).length || 0;

  return (
    <div className="smart-plan-screen">
      <header className="smart-plan-hero">
        <div className="smart-plan-brand">바람따라</div>
        <h1 className="smart-plan-title">실시간 변수에 맞춘 오늘 동선</h1>
        <p className="smart-plan-sub">
          한국관광공사 데이터로 혼잡도를 피하고, 좌표 기준으로 동선 꼬임을 줄인 일정이에요.
        </p>
      </header>

      {loading && (
        <div className="smart-plan-loading">
          <div className="smart-plan-spinner" aria-hidden="true" />
          <p>혼잡도·날씨·거리를 살펴 일정을 짜는 중...</p>
        </div>
      )}

      {error && (
        <div className="error-msg">
          ❌ {error}
          <button type="button" className="btn-skip" onClick={onSkip}>직접 담을래요</button>
        </div>
      )}

      {!loading && plan && (
        <>
          <div className="smart-plan-strategy">
            <span className="smart-plan-strategy-label">추천 전략</span>
            <p>{plan.strategySummary}</p>
            <div className="smart-plan-flags">
              {plan.crowdFiltered && <span className="smart-flag">🚶 붐비는 곳 제외</span>}
              {plan.weatherAdjusted && <span className="smart-flag">🌧️ 실내 전환</span>}
              {plan.heatAdjusted && <span className="smart-flag">🌡️ 폭염 실내</span>}
              {plan.estimatedTotalDistanceKm > 0 && (
                <span className="smart-flag">👣 약 {plan.estimatedTotalDistanceKm}km</span>
              )}
            </div>
          </div>

          {(plan.stops?.length || 0) === 0 ? (
            <p className="empty-state">조건에 맞는 장소를 찾지 못했어요. 카테고리에서 골라보세요.</p>
          ) : (
            <ol className="smart-plan-timeline">
              {plan.stops.map((stop, index) => (
                <li key={stop.contentId} className={`smart-stop ${checked[stop.contentId] ? '' : 'unchecked'}`}>
                  <label className="smart-stop-check">
                    <input
                      type="checkbox"
                      checked={!!checked[stop.contentId]}
                      onChange={() => toggle(stop.contentId)}
                    />
                  </label>
                  <div className="smart-stop-rail">
                    <span className="smart-stop-dot">{index + 1}</span>
                    {index < plan.stops.length - 1 && <span className="smart-stop-line" />}
                  </div>
                  <article className="smart-stop-card">
                    <div className="smart-stop-media">
                      {stop.thumbnailUrl ? (
                        <img src={stop.thumbnailUrl} alt="" loading="lazy" />
                      ) : (
                        <div className="smart-stop-placeholder">🌬️</div>
                      )}
                      {stop.suggestedTime && (
                        <span className="smart-stop-time">{stop.suggestedTime}</span>
                      )}
                    </div>
                    <div className="smart-stop-body">
                      <h3>{stop.placeName}</h3>
                      {stop.oneLiner && <p>{stop.oneLiner}</p>}
                      <div className="smart-stop-meta">
                        {stop.crowdRate != null && (
                          <span className={`crowd-chip ${(100 - stop.crowdRate) >= 40 ? 'good' : 'warn'}`}>
                            여유 {Math.round(100 - stop.crowdRate)}%
                          </span>
                        )}
                        {stop.distanceKm != null && (
                          <span className="leg-chip">이전에서 {stop.distanceKm.toFixed(1)}km</span>
                        )}
                        {stop.category && <span className="leg-chip">{stop.category}</span>}
                      </div>
                    </div>
                  </article>
                </li>
              ))}
            </ol>
          )}

          <footer className="smart-plan-actions">
            <button
              type="button"
              className="btn-primary"
              disabled={confirming || selectedCount === 0}
              onClick={handleConfirm}
            >
              {confirming ? '담는 중...' : `✅ 이 동선으로 시작 (${selectedCount})`}
            </button>
            <button type="button" className="btn-skip" onClick={onBrowseCategories}>
              카테고리에서 더 고르기
            </button>
            <button type="button" className="btn-skip" onClick={onSkip}>
              건너뛰고 직접 담을래요
            </button>
          </footer>
        </>
      )}
    </div>
  );
}
