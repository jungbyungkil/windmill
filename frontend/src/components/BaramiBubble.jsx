/**
 * 바람이(도슨트 캐릭터) 말풍선 — 카드/패널 하단 짧은 코멘트.
 * onActivate가 주어지면 말풍선 전체가 버튼이 된다("...대안을 확인해볼까요?"처럼 행동을
 * 유도하는 코멘트인데 정작 탭이 안 먹던 문제, 2026-09-06 사용자 제보).
 */
export default function BaramiBubble({ comment, compact = false, onActivate, actionLabel }) {
  if (!comment) return null;

  const inner = (
    <>
      <div className="barami-avatar" aria-hidden="true">
        <span className="barami-face">🌬️</span>
      </div>
      <div className="barami-speech">
        <span className="barami-name">바람이</span>
        <p className="barami-text">{comment}</p>
        {onActivate && actionLabel && <span className="barami-cta-hint">{actionLabel} →</span>}
      </div>
    </>
  );

  if (onActivate) {
    return (
      <button
        type="button"
        className={`barami-bubble as-button ${compact ? 'compact' : ''}`}
        onClick={onActivate}
        aria-label={actionLabel ? `${comment} — ${actionLabel}` : comment}
      >
        {inner}
      </button>
    );
  }

  return (
    <div className={`barami-bubble ${compact ? 'compact' : ''}`} role="note">
      {inner}
    </div>
  );
}
