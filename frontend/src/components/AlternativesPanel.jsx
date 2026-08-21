import RecommendationCard from './RecommendationCard';
import useModalHistory from '../hooks/useModalHistory';

const REASON_COPY = {
  RAIN_ALTERNATIVE: {
    title: '🌧️ 변수: 비 소식 · 실내 대안',
    desc: '야외 대신 실내 장소를 골라보세요. 담으면 일정에 반영되고 다른 여행자 참고 데이터로도 남아요.',
  },
  HEAT_ALTERNATIVE: {
    title: '🌡️ 변수: 폭염 · 실내 대안',
    desc: '더위를 피하는 실내 코스예요. 담으면 일정에 반영되고 다른 여행자 참고 데이터로도 남아요.',
  },
  CROWD_ALTERNATIVE: {
    title: '👥 변수: 혼잡 · 한산한 대안',
    desc: '붐비는 곳 대신 여유 있는 장소를 골라보세요. 고르면 일정에 바로 반영돼요.',
  },
  ROUTE_ALTERNATIVE: {
    title: '🔀 변수: 동선 · 가까운 대안',
    desc: '이동을 줄인 순서로 다시 짜거나, 근처 장소로 바꿔 보세요.',
  },
};

export default function AlternativesPanel({
  open,
  candidates,
  loading,
  onAdd,
  addingId,
  onClose,
  reason,
  onApplyAll,
  applyLoading,
}) {
  useModalHistory(open, onClose);
  if (!open) return null;

  const copy = REASON_COPY[reason] || {
    title: '🌬️ 변수 대응 · 대안 코스',
    desc: '감지된 변수에 맞춘 대체 장소예요. 고르면 일정에 반영되고, 기록으로 쌓여 다른 여행자도 참고할 수 있어요.',
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel alternatives-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>{copy.title}</h3>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>
        <p className="modal-desc">{copy.desc}</p>

        {onApplyAll && candidates.length > 0 && !loading && (
          <button
            type="button"
            className="btn-primary alt-apply-all"
            onClick={onApplyAll}
            disabled={applyLoading}
          >
            {applyLoading
              ? '일정 바꾸는 중...'
              : reason === 'CROWD_ALTERNATIVE'
                ? '👥 혼잡한 곳 전부 한산한 곳으로'
                : '🏠 야외 일정 전부 실내로 바꾸기'}
          </button>
        )}

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
