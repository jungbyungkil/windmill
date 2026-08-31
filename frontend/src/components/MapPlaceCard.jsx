import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';
import { HOURS_PHASE_LABEL } from '../utils/hoursPhase';
import { isPlaceInItinerary } from '../utils/itineraryMembership';

/**
 * 지도 마커/리스트에서 고른 장소 카드.
 * 포함 여부는 일정 항목 목록으로 이 안에서 계산한다(호출부가 boolean을 잘못 넘기지 못하게).
 */
export default function MapPlaceCard({
  place,
  itineraryItems = [],
  pendingAddedIds,
  pendingRemovedIds,
  hoursPhase = 'UNKNOWN',
  busy = false,
  onAdd,
  onRemove,
  onClose,
}) {
  if (!place) return null;
  const inItinerary = isPlaceInItinerary(place, itineraryItems, pendingAddedIds, pendingRemovedIds) === true;
  const name = place.placeName || place.title || '이름 없음';
  const dist = place.dist != null ? `${place.dist}m` : null;
  const label = HOURS_PHASE_LABEL[hoursPhase] || HOURS_PHASE_LABEL.UNKNOWN;
  const phaseClass = String(hoursPhase || 'UNKNOWN').toLowerCase().replaceAll('_', '-');

  return (
    <article className="map-place-card">
      {place.thumbnailUrl ? (
        <img className="map-place-card-thumb" src={place.thumbnailUrl} alt="" />
      ) : (
        <div className="map-place-card-thumb map-place-card-thumb-empty" aria-hidden="true">🌬️</div>
      )}
      <div className="map-place-card-body">
        <div className="map-place-card-head">
          <strong>{name}</strong>
          {onClose && (
            <button type="button" className="map-place-card-close" onClick={onClose} aria-label="닫기">
              ×
            </button>
          )}
        </div>
        <div className="map-place-card-meta">
          {place.category && <span className="map-place-chip">{place.category}</span>}
          <span className={`map-place-hours phase-${phaseClass}`}>{label}</span>
          {dist && <span className="map-place-chip muted">{dist}</span>}
          {inItinerary && <span className="map-place-chip added">담김</span>}
        </div>
        {place.addr1 && <p className="map-place-card-addr">{place.addr1}</p>}
        <div className="map-place-card-actions">
          {inItinerary ? (
            <button
              type="button"
              className="map-place-card-cta danger"
              onClick={() => onRemove?.(place)}
              disabled={busy}
            >
              {busy ? '빼는 중…' : '일정에서 제거'}
            </button>
          ) : (
            <button
              type="button"
              className="map-place-card-cta"
              onClick={() => onAdd?.(place)}
              disabled={busy}
            >
              {busy ? '담는 중…' : '일정에 추가'}
            </button>
          )}
          {canOpenInKakaoMap(place) && (
            <button
              type="button"
              className="map-place-card-kakao"
              onClick={() => openInKakaoMap(place)}
            >
              카카오맵
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
