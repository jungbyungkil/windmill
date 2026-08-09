/**
 * 바람이(도슨트 캐릭터) 말풍선 — 카드/패널 하단 짧은 코멘트.
 */
export default function BaramiBubble({ comment, compact = false }) {
  if (!comment) return null;
  return (
    <div className={`barami-bubble ${compact ? 'compact' : ''}`} role="note">
      <div className="barami-avatar" aria-hidden="true">
        <span className="barami-face">🌬️</span>
      </div>
      <div className="barami-speech">
        <span className="barami-name">바람이</span>
        <p className="barami-text">{comment}</p>
      </div>
    </div>
  );
}
