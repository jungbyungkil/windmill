import ItineraryItemCard from './ItineraryItemCard';

export default function ItineraryList({
  items,
  affectedItemIds = [],
  weatherAlert = false,
  onUpdateTime,
  onTogglePin,
  onDelete,
  onOpenDocent,
}) {
  const affected = new Set((affectedItemIds || []).map(Number));

  return (
    <div className="itinerary-list">
      <h2 className="section-title">담은 일정</h2>
      {weatherAlert && affected.size > 0 && (
        <p className="itinerary-weather-hint">
          빨간 표시된 야외 일정은 비·폭염 영향권이에요. 바람개비에서 실내 일정으로 바꿔보세요.
        </p>
      )}
      {items.length === 0 ? (
        <p className="empty-state">아직 담은 장소가 없어요. 아래에서 추천을 받아보세요.</p>
      ) : (
        <div className="item-cards">
          {items.map((item) => (
            <ItineraryItemCard
              key={item.itemId}
              item={item}
              alerted={affected.has(Number(item.itemId))}
              onUpdateTime={onUpdateTime}
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
