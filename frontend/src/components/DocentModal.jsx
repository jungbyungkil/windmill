export default function DocentModal({ open, placeName, script, audioUrl, loading, error, onClose }) {
  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel docent-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>🎧 바람이의 도슨트</h3>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>

        <div className="docent-card">
          <div className="docent-avatar">🌬️</div>
          <div className="docent-place">{placeName}</div>

          {loading && (
            <div className="skeleton-list">
              <div className="skeleton-line" />
              <div className="skeleton-line" />
              <div className="skeleton-line short" />
            </div>
          )}

          {!loading && error && <p className="error-msg">❌ {error}</p>}

          {!loading && !error && audioUrl && (
            <audio className="docent-audio" controls src={audioUrl}>
              브라우저가 오디오 재생을 지원하지 않아요.
            </audio>
          )}

          {!loading && !error && script && (
            <blockquote className="docent-script">{script}</blockquote>
          )}
        </div>
      </div>
    </div>
  );
}
