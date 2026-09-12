import RecommendationCard from './RecommendationCard';
import BaramiBubble from './BaramiBubble';
import useModalHistory from '../hooks/useModalHistory';
import { RainIcon, HeatIcon, CrowdIcon } from './Icons';

const REASON_COPY = {
  RAIN_ALTERNATIVE: {
    title: <><RainIcon size={18} /> 비 소식 · 실내 대안</>,
    desc: '야외 대신 실내 장소예요. 담으면 일정에 바로 반영되고, 다른 여행자 참고 기록으로도 남아요.',
    barami: '비가 와도 즐겁게! 실내로 갈 만한 곳들을 모아왔어요.',
  },
  HEAT_ALTERNATIVE: {
    title: <><HeatIcon size={18} /> 폭염 · 실내 대안</>,
    desc: '더위를 피하는 실내 코스예요. 담으면 일정에 바로 반영돼요.',
    barami: '너무 덥죠? 시원한 실내 위주로 골라왔어요.',
  },
  CROWD_ALTERNATIVE: {
    title: <><CrowdIcon size={18} /> 혼잡 · 한산한 대안</>,
    desc: '붐비는 곳 대신 여유 있는 장소예요. 고르면 일정에 바로 반영돼요.',
    barami: '사람 많은 곳 말고, 지금 한산한 데를 찾아왔어요.',
  },
  ROUTE_ALTERNATIVE: {
    title: '🔀 동선 · 근처 다른 장소',
    desc: '이동을 줄이도록 지금 위치에서 가까운 장소로 바꿔 보세요.',
    barami: '동선이 꼬였네요. 근처에서 바로 갈 만한 곳을 찾아왔어요!',
  },
  NEARBY_ANY: {
    title: '🌬️ 바람이가 찾은 근처 장소',
    desc: '지금 위치에서 가까운 곳들이에요. 카페·맛집·쇼핑·관광 가리지 않고 지금 갈 수 있는 곳을 모아왔어요.',
    barami: '지금 여기서 바로 갈 수 있는 곳들이에요. 마음에 드는 곳을 담아보세요!',
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
  /** 🔴 urgent(DANGER)일 때만 "전부 바꾸기" 일괄 버튼을 목록 하단 보조 옵션으로 노출 */
  bulkUrgent = false,
  applyLoading,
  error,
  onRetry,
}) {
  useModalHistory(open, onClose);
  if (!open) return null;

  const copy = REASON_COPY[reason] || {
    title: '🌬️ 대안 코스',
    desc: '감지된 변수에 맞춘 대체 장소예요. 고르면 일정에 반영되고, 기록으로 쌓여 다른 여행자도 참고할 수 있어요.',
    barami: '이 상황에 맞는 곳들을 골라왔어요. 편한 곳으로 담아보세요.',
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel alternatives-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>{copy.title}</h3>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>
        <p className="modal-desc">{copy.desc}</p>
        {copy.barami && !loading && !error && <BaramiBubble compact comment={copy.barami} />}

        {loading ? (
          <div className="skeleton-list">
            <div className="skeleton-card" />
            <div className="skeleton-card" />
          </div>
        ) : error ? (
          <div className="empty-state">
            <p>{error}</p>
            {onRetry && (
              <button type="button" className="btn-secondary" onClick={onRetry}>
                다시 시도
              </button>
            )}
          </div>
        ) : candidates.length === 0 ? (
          <div className="empty-state">
            <p>이 근처에서 지금 갈 만한 곳을 찾지 못했어요. 잠시 뒤 다시 눌러 보세요.</p>
            {onRetry && (
              <button type="button" className="btn-secondary" onClick={onRetry}>
                다시 찾기
              </button>
            )}
          </div>
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

        {bulkUrgent && onApplyAll && candidates.length > 0 && !loading && !error && (
          <div className="alt-apply-all-wrap">
            <p className="alt-apply-all-hint">
              하나씩 고르기 어렵다면, 한 번에 바꿀 수도 있어요.
            </p>
            <button
              type="button"
              className="btn-secondary alt-apply-all"
              onClick={onApplyAll}
              disabled={applyLoading}
            >
              {applyLoading
                ? '일정 바꾸는 중...'
                : reason === 'CROWD_ALTERNATIVE'
                  ? '👥 혼잡한 곳 전부 한산한 곳으로'
                  : '🏠 야외 일정 전부 실내로 바꾸기'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
