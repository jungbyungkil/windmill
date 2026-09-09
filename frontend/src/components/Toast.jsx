/**
 * 액션 즉시 피드백 토스트 — 동선 최적화 / 혼잡도 대안이 같은 스타일로 공유한다.
 * 노출 2초는 useResolveFeedback(TOAST_MS)가 관리하고, 여기선 표시만 한다.
 */
export default function Toast({ toast, onDismiss }) {
  if (!toast) return null;
  const tone = toast.tone === 'error' ? 'error' : 'success';
  return (
    <div
      className={`wf-toast wf-toast-${tone}`}
      role="status"
      aria-live="polite"
      onClick={onDismiss}
    >
      <span className="wf-toast-icon" aria-hidden="true">{tone === 'error' ? '⚠️' : '🍃'}</span>
      <span className="wf-toast-text">{toast.text}</span>
    </div>
  );
}
