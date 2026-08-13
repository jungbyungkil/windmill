/** Sentry ErrorBoundary 폴백 - UI 크래시 시 노출(에러 자체는 Sentry가 자동 캡처) */
export default function ErrorFallback() {
  return (
    <div className="error-fallback" role="alert">
      <p className="error-fallback-emoji" aria-hidden="true">🌬️</p>
      <p className="error-fallback-message">일시적인 오류가 발생했어요.</p>
      <button type="button" className="btn-primary" onClick={() => window.location.reload()}>
        새로고침
      </button>
    </div>
  );
}
