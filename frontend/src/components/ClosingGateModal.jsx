import useModalHistory from '../hooks/useModalHistory';

/**
 * 마감 임박/마감으로 장소 선택 불가 안내.
 */
export default function ClosingGateModal({ open, message, placeName, onClose }) {
  useModalHistory(open, onClose);
  if (!open) return null;
  return (
    <div className="closing-gate-backdrop" role="presentation" onClick={onClose}>
      <div
        className="closing-gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="closing-gate-title"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="closing-gate-eyebrow">마감 임박 / 선택 불가</p>
        <h2 id="closing-gate-title" className="closing-gate-title">
          {placeName || '이 장소'}
        </h2>
        <p className="closing-gate-message">{message}</p>
        <p className="closing-gate-hint">다른 장소를 다시 골라 보세요.</p>
        <button type="button" className="closing-gate-ok" onClick={onClose}>
          확인
        </button>
      </div>
    </div>
  );
}
