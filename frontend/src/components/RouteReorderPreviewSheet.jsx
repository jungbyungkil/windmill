import useModalHistory from '../hooks/useModalHistory';

/**
 * 동선 재배치 "변경 전/후 미리보기" 시트 - 즉시 적용 대신 확인 후 적용(2026-09-14 핸드오프 브리프
 * Phase 7, 확정 결정: 즉시 적용은 되돌리기 비용이 큼). 적용은 기존 "동선 다시" 로직을 그대로 재사용.
 */
export default function RouteReorderPreviewSheet({
  open,
  loading,
  error,
  preview,
  applying,
  onCancel,
  onApply,
}) {
  useModalHistory(open, onCancel);
  if (!open) return null;

  return (
    <div className="route-preview-backdrop" role="presentation" onClick={onCancel}>
      <div
        className="route-preview-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="route-preview-title"
        onClick={(e) => e.stopPropagation()}
      >
        <button type="button" className="modal-close-x" aria-label="닫기" onClick={onCancel}>✕</button>
        <h2 id="route-preview-title" className="route-preview-title">순서를 이렇게 바꿀까요?</h2>

        {loading && <p className="route-preview-loading">불러오는 중...</p>}
        {!loading && error && <p className="error-msg">{error}</p>}

        {!loading && !error && preview && (
          <>
            <div className="route-preview-columns">
              <div className="route-preview-col">
                <p className="route-preview-col-label">지금 순서</p>
                <ol className="route-preview-stop-list">
                  {preview.before.map((stop) => (
                    <li key={`before-${stop.itemId}`}>
                      <span className="route-preview-stop-time">{stop.scheduledTime || '--:--'}</span>
                      <span className="route-preview-stop-name">{stop.placeName}</span>
                    </li>
                  ))}
                </ol>
              </div>
              <div className="route-preview-arrow" aria-hidden="true">→</div>
              <div className="route-preview-col">
                <p className="route-preview-col-label">재배치 후</p>
                <ol className="route-preview-stop-list">
                  {preview.after.map((stop) => (
                    <li key={`after-${stop.itemId}`}>
                      <span className="route-preview-stop-time">{stop.scheduledTime || '--:--'}</span>
                      <span className="route-preview-stop-name">{stop.placeName}</span>
                    </li>
                  ))}
                </ol>
              </div>
            </div>
            {preview.message && <p className="route-preview-message">{preview.message}</p>}
          </>
        )}

        <div className="route-preview-actions">
          <button type="button" className="route-preview-cancel" onClick={onCancel} disabled={applying}>
            취소
          </button>
          <button
            type="button"
            className="route-preview-confirm"
            onClick={onApply}
            disabled={loading || Boolean(error) || applying}
          >
            {applying ? '적용하는 중...' : '이대로 적용'}
          </button>
        </div>
      </div>
    </div>
  );
}
