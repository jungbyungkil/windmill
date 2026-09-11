import useModalHistory from '../hooks/useModalHistory';

/**
 * 일정 추가/시간 수정 시 휴무·마감 안내. 저장을 막지 않고 담을지/취소할지만 고른다.
 */
export default function HoursWarningModal({
  open,
  placeName,
  warnings = [],
  confirmLabel = '그래도 담기',
  onCancel,
  onConfirm,
}) {
  useModalHistory(open, onCancel);
  if (!open) return null;
  const list = Array.isArray(warnings) ? warnings.filter((w) => w?.message) : [];
  const firstCode = list[0]?.code;
  const eyebrow = firstCode === 'CLOSING_SOON' ? '마감 안내' : '휴무 안내';

  return (
    <div className="hours-warning-backdrop" role="presentation" onClick={onCancel}>
      <div
        className="hours-warning-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="hours-warning-title"
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="modal-close-x" aria-label="닫기" onClick={onCancel}>✕</button>
        <p className="hours-warning-eyebrow">{eyebrow}</p>
        <h2 id="hours-warning-title" className="hours-warning-title">
          {placeName || '이 장소'}
        </h2>
        {list.map((warning) => (
          <div key={warning.code || warning.message} className="hours-warning-block">
            <p className="hours-warning-message">{warning.message}</p>
            {warning.detail && (
              <p className="hours-warning-detail">
                {warning.code === 'CLOSING_SOON' ? warning.detail : `정기휴무: ${warning.detail}`}
              </p>
            )}
          </div>
        ))}
        <p className="hours-warning-hint">그래도 일정에 넣을까요? 원하시면 나중에 언제든 뺄 수 있어요.</p>
        <div className="hours-warning-actions">
          <button type="button" className="hours-warning-cancel" onClick={onCancel}>
            취소
          </button>
          <button type="button" className="hours-warning-confirm" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
