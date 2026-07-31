import RecommendationCard from './RecommendationCard';

export default function AlternativesPanel({ open, candidates, loading, onAdd, addingId, onClose }) {
  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel alternatives-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>🌬️ 바람이 알려준 대안 코스</h3>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>

        {loading ? (
          <div className="skeleton-list">
            <div className="skeleton-card" />
            <div className="skeleton-card" />
          </div>
        ) : candidates.length === 0 ? (
          <p className="empty-state">지금은 추천할 대안이 없어요. 잠시 후 다시 시도해보세요.</p>
        ) : (
          <div className="reco-grid">
            {candidates.map((c) => (
              <RecommendationCard
                key={c.contentId}
                candidate={c}
                onAdd={onAdd}
                adding={addingId === c.contentId}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
