function formatDayOption(dateStr, index) {
  const d = new Date(dateStr + 'T00:00:00');
  return `${index + 1}일차 (${d.getMonth() + 1}/${d.getDate()})`;
}

export default function ItineraryItemCard({
  item,
  alerted = false,
  tripDates = [],
  onUpdateTime,
  onTogglePin,
  onDelete,
  onOpenDocent,
  onMoveDay,
}) {
  const multiDay = tripDates.length > 1;
  const currentDate = item.visitDate || tripDates[0];

  return (
    <div className={`item-card ${item.pinned ? 'pinned' : ''} ${alerted ? 'weather-affected' : ''}`}>
      {item.thumbnailUrl ? (
        <img className="item-thumb" src={item.thumbnailUrl} alt={item.placeName} loading="lazy" />
      ) : (
        <div className="item-thumb item-thumb-placeholder">🌬️</div>
      )}

      <input
        type="time"
        className="item-time"
        value={item.scheduledTime || ''}
        onChange={(e) => onUpdateTime(item.itemId, e.target.value)}
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

        {multiDay && onMoveDay && (
          <label className="item-move-day">
            <span>일차</span>
            <select
              value={currentDate || ''}
              onChange={(e) => onMoveDay(item.itemId, e.target.value)}
              onClick={(e) => e.stopPropagation()}
            >
              {tripDates.map((date, i) => (
                <option key={date} value={date}>{formatDayOption(date, i)}</option>
              ))}
            </select>
          </label>
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
