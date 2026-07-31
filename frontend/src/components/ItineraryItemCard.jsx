export default function ItineraryItemCard({ item, onUpdateTime, onTogglePin, onDelete, onOpenDocent }) {
  return (
    <div className={`item-card ${item.pinned ? 'pinned' : ''}`}>
      <input
        type="time"
        className="item-time"
        value={item.scheduledTime || ''}
        onChange={(e) => onUpdateTime(item.itemId, e.target.value)}
      />

      <div className="item-body">
        <div className="item-head">
          <span className="item-name">{item.placeName}</span>
          {item.pinned && <span className="pin-badge" title={item.pinnedReason || '고정됨'}>📌</span>}
        </div>

        {item.tags?.length > 0 && (
          <div className="item-tags">
            {item.tags.map((t) => <span key={t} className="tag-chip">{t}</span>)}
          </div>
        )}

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
