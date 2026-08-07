export default function ItineraryItemCard({
  item,
  alerted = false,
  onUpdateTime,
  onTogglePin,
  onDelete,
  onOpenDocent,
}) {
  return (
    <div className={`item-card ${item.pinned ? 'pinned' : ''} ${alerted ? 'weather-affected' : ''}`}>
      {item.thumbnailUrl ? (
        <img className="item-thumb" src={item.thumbnailUrl} alt={item.placeName} loading="lazy" />
      ) : (
        <div className="item-thumb item-thumb-placeholder">🌬️</div>
      )}

      <input
        type="text"
        className="item-time"
        inputMode="numeric"
        placeholder="09:00"
        aria-label="방문 시각"
        maxLength={5}
        value={item.scheduledTime || ''}
        onChange={(e) => {
          const next = e.target.value.replace(/[^\d:]/g, '').slice(0, 5);
          onUpdateTime(item.itemId, next);
        }}
        onBlur={(e) => {
          const raw = (e.target.value || '').trim();
          if (!raw) return;
          const m = raw.match(/^(\d{1,2}):?(\d{0,2})$/);
          if (!m) return;
          const hh = Math.min(23, Number(m[1]));
          const mm = Math.min(59, Number(m[2] || '0'));
          onUpdateTime(item.itemId, `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`);
        }}
      />

      <div className="item-body">
        <div className="item-head">
          <span className="item-name">{item.placeName}</span>
          {alerted && <span className="weather-affected-badge" title="비·폭염 영향">⚠️ 야외</span>}
          {item.pinned && <span className="pin-badge" title={item.pinnedReason || '고정됨'}>📌</span>}
        </div>

        {item.tags?.length > 0 && (
          <div className="item-tags">
            {item.tags.map((t) => <span key={t} className="tag-chip">{t}</span>)}
          </div>
        )}

        <div className="reco-info">
          {item.addr1 && <div className="reco-info-row">📍 {item.addr1}</div>}
          {(item.isFree || item.useFeeText) && (
            <div className="reco-info-row">🎫 {item.isFree ? '무료' : item.useFeeText}</div>
          )}
          {item.tel && <div className="reco-info-row">☎️ {item.tel}</div>}
          {item.restDateText && <div className="reco-info-row reco-restdate">🚫 정기휴무: {item.restDateText}</div>}
        </div>

        {item.crowdRate !== null && item.crowdRate !== undefined && (
          <div className="item-crowd">혼잡도 {Math.round(item.crowdRate)}%</div>
        )}
      </div>

      <div className="item-actions">
        <button
          className="icon-btn"
          title={item.pinned ? '고정 해제' : '고정하기'}
          onClick={() => onTogglePin(item.itemId, !item.pinned)}
        >
          {item.pinned ? '📌' : '📍'}
        </button>
        <button className="icon-btn" title="AI 도슨트 듣기" onClick={() => onOpenDocent(item)}>🎧</button>
        <button className="icon-btn danger" title="삭제" onClick={() => onDelete(item.itemId)}>🗑️</button>
      </div>
    </div>
  );
}
