export default function DocentModal({ open, placeName, script, loading, error, onClose }) {
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

          {!loading && !error && script && (
            <blockquote className="docent-script">{script}</blockquote>
          )}
        </div>
      </div>
    </div>
  );
}
