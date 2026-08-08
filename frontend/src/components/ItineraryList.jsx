import ItineraryItemCard from './ItineraryItemCard';

export default function ItineraryList({
  items,
  affectedItemIds = [],
  weatherAlert = false,
  dayLabel,
  onUpdateTime,
  onUpdateItem,
  onTogglePin,
  onDelete,
  onOpenDocent,
  onPlanDay,
  onSortByTime,
  sortByTimeLoading = false,
}) {
  const affected = new Set((affectedItemIds || []).map(Number));

  return (
    <div className="itinerary-list">
      <div className="itinerary-list-head">
        <h2 className="section-title">{dayLabel ? `${dayLabel} 일정` : '담은 일정'}</h2>
        <div className="itinerary-list-actions">
          {items.length > 1 && onSortByTime && (
            <button
              type="button"
              className="btn-sort-time"
              onClick={onSortByTime}
              disabled={sortByTimeLoading}
            >
              {sortByTimeLoading ? '정렬 중…' : '⏱ 시간순 정렬'}
            </button>
          )}
          {items.length > 0 && (
            <span className="itinerary-count">{items.length}곳</span>
          )}
        </div>
      </div>
      {weatherAlert && affected.size > 0 && (
        <p className="itinerary-weather-hint">
          빨간 표시된 야외 일정은 비·폭염 영향권이에요. 바람개비에서 실내 일정으로 바꿔보세요.
        </p>
      )}
      {items.length === 0 ? (
        <div className="itinerary-empty">
          <p className="empty-state">아직 담은 장소가 없어요.</p>
          {onPlanDay && (
            <button type="button" className="btn-primary" onClick={onPlanDay}>
              🌬️ 스마트 일정 짜기
            </button>
          )}
          <p className="itinerary-empty-hint">아래에서 장소를 검색해 직접 담을 수도 있어요.</p>
        </div>
      ) : (
        <div className="item-cards">
          {items.map((item) => (
            <ItineraryItemCard
              key={item.itemId}
              item={item}
              alerted={affected.has(Number(item.itemId))}
              onUpdateTime={onUpdateTime}
              onUpdateItem={onUpdateItem}
              onTogglePin={onTogglePin}
              onDelete={onDelete}
              onOpenDocent={onOpenDocent}
            />
          ))}
        </div>
      )}
    </div>
  );
}
