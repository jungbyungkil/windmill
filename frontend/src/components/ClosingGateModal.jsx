import useModalHistory from '../hooks/useModalHistory';

const COPY = {
  CLOSING: { eyebrow: '마감 임박 / 선택 불가', hint: '다른 장소를 다시 골라 보세요.' },
  CONFLICT: { eyebrow: '시간 겹침', hint: '다른 시간을 골라 보세요.' },
};

/**
 * 장소 선택/시간 지정 불가 안내 - 원인이 둘이라 kind로 문구를 구분한다:
 * CLOSING(기본값) = 이 장소 자체의 마감시간 임박, CONFLICT = 같은 날 다른 일정과 시간대 겹침.
 */
export default function ClosingGateModal({ open, message, placeName, kind = 'CLOSING', onClose }) {
  useModalHistory(open, onClose);
  if (!open) return null;
  const copy = COPY[kind] || COPY.CLOSING;
  return (
    <div className="closing-gate-backdrop" role="presentation" onClick={onClose}>
      <div
        className="closing-gate-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="closing-gate-title"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="closing-gate-eyebrow">{copy.eyebrow}</p>
        <h2 id="closing-gate-title" className="closing-gate-title">
          {placeName || '이 장소'}
        </h2>
        <p className="closing-gate-message">{message}</p>
        <p className="closing-gate-hint">{copy.hint}</p>
        <button type="button" className="closing-gate-ok" onClick={onClose}>
          확인
        </button>
      </div>
    </div>
  );
}
