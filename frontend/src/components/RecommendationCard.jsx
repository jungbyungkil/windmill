export default function RecommendationCard({ candidate, onAdd, adding }) {
  return (
    <div className="reco-card">
      {candidate.thumbnailUrl ? (
        <img className="reco-thumb" src={candidate.thumbnailUrl} alt={candidate.placeName} loading="lazy" />
      ) : (
        <div className="reco-thumb reco-thumb-placeholder">🌬️</div>
      )}

      <div className="reco-head">
        <span className="reco-rank">#{candidate.rank}</span>
        <span className="reco-name">{candidate.placeName}</span>
        {candidate.category && <span className="reco-category">{candidate.category}</span>}
      </div>

      {candidate.oneLiner && <p className="reco-oneliner">{candidate.oneLiner}</p>}

      <div className="reco-meta">
        {candidate.freeRatePercent !== null && candidate.freeRatePercent !== undefined && (
          <span className="reco-free-rate">여유율 {Math.round(candidate.freeRatePercent)}%</span>
        )}
        {candidate.matchedTags?.map((t) => <span key={t} className="tag-chip">{t}</span>)}
      </div>

      <button className="btn-add" onClick={() => onAdd(candidate)} disabled={adding}>
        {adding ? '담는 중...' : '+ 일정에 추가'}
      </button>
    </div>
  );
}
