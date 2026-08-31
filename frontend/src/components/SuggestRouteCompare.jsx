import useModalHistory from '../hooks/useModalHistory';

function Timeline({ title, stops, variant }) {
  const list = Array.isArray(stops) ? stops : [];
  const firstHard = list.findIndex((s) => s.visitHardToday);
  let visitable = 0;
  const numbers = list.map((s) => (s.visitHardToday ? '–' : ++visitable));
  return (
    <div className={`suggest-route-col suggest-route-col--${variant}`}>
      <h3>{title}</h3>
      {list.length === 0 ? (
        <p className="suggest-route-empty">장소가 없어요</p>
      ) : (
        <ol className="suggest-route-track">
          {list.map((stop, index) => (
            <li
              key={`${stop.itemId}-${index}`}
              className={`suggest-route-stop${stop.visitHardToday ? ' is-hard' : ''}`}
            >
              {firstHard === index && (
                <p className="suggest-route-hard-divider">오늘 방문 어려움</p>
              )}
              <div className="suggest-route-stop-row">
                <span className="suggest-route-num">{numbers[index]}</span>
                <span className="suggest-route-label">
                  <strong>{stop.scheduledTime || '--:--'}</strong>
                  <em>{stop.placeName}</em>
                  {stop.visitHardToday && (
                    <span className="suggest-route-hard-badge">
                      {stop.hardTodayLabel || '오늘 방문 어려움'}
                    </span>
                  )}
                </span>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

/**
 * 그리디 재배열 제안. 확인 전까지 일정을 바꾸지 않는다.
 */
export default function SuggestRouteCompare({
  open,
  loading,
  applying,
  error,
  result,
  onApply,
  onClose,
}) {
  useModalHistory(open, onClose);
  if (!open) return null;

  const canApply = Boolean(result?.suggestedStops?.length)
    && (result.orderChanged || result.timesChanged)
    && !loading
    && !applying;

  return (
    <div className="suggest-route-backdrop" role="presentation" onClick={onClose}>
      <div
        className="suggest-route-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="suggest-route-title"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="suggest-route-eyebrow">이 순서 어때요?</p>
        <h2 id="suggest-route-title" className="suggest-route-title">
          지금 위치와 시각으로 순서를 다시 잡아 봤어요
        </h2>
        {loading && <p className="suggest-route-status">순서를 계산하는 중이에요…</p>}
        {error && <p className="suggest-route-error">{error}</p>}
        {!loading && result && (
          <>
            <p className="suggest-route-message">{result.message}</p>
            {result.suggestedDistanceKm != null && result.currentDistanceKm != null && (
              <p className="suggest-route-km">
                이동 {Number(result.currentDistanceKm).toFixed(1)}km
                {result.suggestedDistanceKm !== result.currentDistanceKm
                  ? ` → 약 ${Number(result.suggestedDistanceKm).toFixed(1)}km`
                  : ''}
              </p>
            )}
            <div className="suggest-route-compare">
              <Timeline title="기존 순서" stops={result.currentStops} variant="current" />
              <Timeline title="제안 순서" stops={result.suggestedStops} variant="suggested" />
            </div>
          </>
        )}
        <div className="suggest-route-actions">
          <button type="button" className="suggest-route-keep" onClick={onClose} disabled={applying}>
            기존 순서 유지
          </button>
          <button
            type="button"
            className="suggest-route-apply"
            onClick={onApply}
            disabled={!canApply}
          >
            {applying ? '반영 중…' : '이 순서로 변경'}
          </button>
        </div>
      </div>
    </div>
  );
}
