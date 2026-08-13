import useModalHistory from '../hooks/useModalHistory';

/**
 * 같은 날짜에 이미 진행 중인 당일치기가 있을 때(409) 안내.
 * "기존 일정 수정" → 그 일정으로 이동, "새로 만들기" → 기존 일정을 덮어쓰고 새로 생성.
 */
export default function DuplicateItineraryModal({
  open,
  existing,
  dateLabel,
  overwriting,
  onEditExisting,
  onOverwrite,
  onClose,
}) {
  useModalHistory(open, onClose);
  if (!open || !existing) return null;

  const placeNames = (existing.placeNames || []).filter(Boolean);

  return (
    <div className="duplicate-itinerary-backdrop" role="presentation" onClick={onClose}>
      <div
        className="duplicate-itinerary-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="duplicate-itinerary-title"
        onClick={(e) => e.stopPropagation()}
      >
        <p className="duplicate-itinerary-eyebrow">일정 중복</p>
        <h2 id="duplicate-itinerary-title" className="duplicate-itinerary-title">
          이미 {dateLabel} 일정이 있어요
        </h2>
        <p className="duplicate-itinerary-message">
          수정하시겠어요? 기존 일정을 이어서 편집하거나, 새로 만들면 기존 일정은 삭제돼요.
        </p>

        <div className="duplicate-itinerary-summary">
          {existing.regionDisplayName && (
            <span className="duplicate-itinerary-region">{existing.regionDisplayName}</span>
          )}
          {placeNames.length > 0 ? (
            <p className="duplicate-itinerary-places">{placeNames.join(', ')}</p>
          ) : (
            <p className="duplicate-itinerary-places duplicate-itinerary-places-empty">아직 담긴 장소가 없어요</p>
          )}
        </div>

        <div className="duplicate-itinerary-actions">
          <button
            type="button"
            className="duplicate-itinerary-edit"
            onClick={onEditExisting}
            disabled={overwriting}
          >
            기존 일정 수정
          </button>
          <button
            type="button"
            className="duplicate-itinerary-overwrite"
            onClick={onOverwrite}
            disabled={overwriting}
          >
            {overwriting ? '만드는 중...' : '새로 만들기(덮어쓰기)'}
          </button>
        </div>
      </div>
    </div>
  );
}
