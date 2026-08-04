function formatDate(yyyyMMdd) {
  if (!yyyyMMdd || yyyyMMdd.length !== 8) return yyyyMMdd;
  return `${yyyyMMdd.slice(4, 6)}.${yyyyMMdd.slice(6, 8)}`;
}

export default function FestivalBanner({ festivals, onAdd, addingId }) {
  if (!festivals || festivals.length === 0) return null;

  return (
    <div className="festival-banner">
      <div className="festival-banner-title">🎉 이 기간, 이 지역 축제예요</div>
      <div className="festival-card-list">
        {festivals.map((f) => (
          <div key={f.contentId} className="festival-card">
            {f.thumbnailUrl && <img className="festival-thumb" src={f.thumbnailUrl} alt={f.placeName} />}
            <div className="festival-info">
              <div className="festival-name">{f.placeName}</div>
              <div className="festival-period">{formatDate(f.eventStartDate)} ~ {formatDate(f.eventEndDate)}</div>
              {f.addr1 && <div className="festival-addr">{f.addr1}</div>}
            </div>
            <button
              className="btn-pinwheel-cta secondary festival-add-btn"
              onClick={() => onAdd?.(f)}
              disabled={addingId === f.contentId}
            >
              {addingId === f.contentId ? '추가 중...' : '일정에 추가'}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
