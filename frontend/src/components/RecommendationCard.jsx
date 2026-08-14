import { openExternalLink } from '../utils/externalLink';
import { recordView } from '../utils/viewHistory';

const BADGE_ICON = { WEATHER: '🌧️', CONGESTION: '🚶', HOURS: '🕐' };

export default function RecommendationCard({ candidate, onAdd, adding, nextCandidates = [], addLabel = '+ 일정에 추가', addingLabel = '담는 중...' }) {
  return (
    <article className="reco-card">
      <div className="reco-media">
        {candidate.thumbnailUrl ? (
          <img className="reco-thumb" src={candidate.thumbnailUrl} alt={candidate.placeName} loading="lazy" />
        ) : (
          <div className="reco-thumb reco-thumb-placeholder">🌬️</div>
        )}
        <div className="reco-media-overlay">
          <span className="reco-rank">#{candidate.rank}</span>
          {candidate.crowdRate != null && (
            <span className="reco-popular-chip">인기 {Math.round(candidate.crowdRate)}</span>
          )}
        </div>
      </div>

      <div className="reco-head">
        <span className="reco-name">{candidate.placeName}</span>
        {candidate.category && <span className="reco-category">{candidate.category}</span>}
      </div>

      {candidate.badges?.length > 0 && (
        <div className="reco-badges">
          {candidate.badges.map((b, i) => (
            <span key={i} className={`reco-badge reco-badge-${b.severity?.toLowerCase()}`}>
              {BADGE_ICON[b.type] || ''} {b.label}
            </span>
          ))}
        </div>
      )}

      {candidate.oneLiner && <p className="reco-oneliner">{candidate.oneLiner}</p>}

      <div className="reco-info">
        {candidate.addr1 && <div className="reco-info-row">📍 {candidate.addr1}</div>}
        {(candidate.isFree || candidate.useFeeText) && (
          <div className="reco-info-row">🎫 {candidate.isFree ? '무료' : candidate.useFeeText}</div>
        )}
        {candidate.tel && <div className="reco-info-row">☎️ {candidate.tel}</div>}
        {candidate.strollerFriendly === true && <div className="reco-info-row">🍼 유모차 이용 가능</div>}
        {candidate.accessibleFriendly && <div className="reco-info-row">♿ 무장애 시설</div>}
        {candidate.restDateText && <div className="reco-info-row reco-restdate">🚫 정기휴무: {candidate.restDateText}</div>}
        {candidate.closeTime && (
          <div className="reco-info-row reco-close">🕐 마감 {candidate.closeTime}{candidate.useTimeText ? ` · ${candidate.useTimeText}` : ''}</div>
        )}
        {!candidate.closeTime && candidate.useTimeText && (
          <div className="reco-info-row reco-close">🕐 {candidate.useTimeText}</div>
        )}
        {candidate.homepageUrl && (
          <button
            type="button"
            className="reco-info-row reco-info-link"
            onClick={() => {
              recordView({ type: 'place', id: candidate.contentId, name: candidate.placeName, thumbnail: candidate.thumbnailUrl });
              openExternalLink(candidate.homepageUrl);
            }}
          >
            🔗 홈페이지
          </button>
        )}
      </div>

      <div className="reco-meta">
        {candidate.distanceKm !== null && candidate.distanceKm !== undefined && (
          <span className="reco-distance">👣 {candidate.distanceKm.toFixed(1)}km</span>
        )}
        {candidate.freeRatePercent !== null && candidate.freeRatePercent !== undefined && (
          <span className="reco-free-rate">여유율 {Math.round(candidate.freeRatePercent)}%</span>
        )}
        {candidate.matchedTags?.map((t) => <span key={t} className="tag-chip">{t}</span>)}
      </div>

      <button type="button" className="btn-add" onClick={() => onAdd(candidate)} disabled={adding}>
        {adding ? addingLabel : addLabel}
      </button>

      {nextCandidates.length > 0 && (
        <div className="reco-chain">
          <span className="reco-chain-label">다음 코스로 어때요</span>
          <div className="reco-chain-list">
            {nextCandidates.map((n) => (
              <span key={n.contentId} className="reco-chain-item">
                {n.placeName}
                {n.distanceKm !== null && n.distanceKm !== undefined && ` · ${n.distanceKm.toFixed(1)}km`}
              </span>
            ))}
          </div>
        </div>
      )}
    </article>
  );
}
