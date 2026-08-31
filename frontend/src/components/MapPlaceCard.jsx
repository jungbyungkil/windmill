import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';
import { HOURS_PHASE_LABEL } from '../utils/hoursPhase';

/**
 * 지도 마커/리스트에서 고른 장소 카드.
 * 검색 결과 필드만 쓰고, 이미 일정에 있으면 "제거"로 전환한다.
 */
export default function MapPlaceCard({
  place,
  inItinerary = false,
  hoursPhase = 'UNKNOWN',
  busy = false,
  onAdd,
  onRemove,
  onClose,
}) {
  if (!place) return null;
  const name = place.placeName || place.title || '이름 없음';
  const dist = place.dist != null ? `${place.dist}m` : null;
  const label = HOURS_PHASE_LABEL[hoursPhase] || HOURS_PHASE_LABEL.UNKNOWN;
  const phaseClass = String(hoursPhase || 'UNKNOWN').toLowerCase().replace('_', '-');

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
