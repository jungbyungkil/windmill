import ItineraryItemCard from './ItineraryItemCard';

export default function ItineraryList({ items, onUpdateTime, onTogglePin, onDelete, onOpenDocent }) {
  return (
    <div className="itinerary-list">
      <h2 className="section-title">담은 일정</h2>
      {items.length === 0 ? (
        <p className="empty-state">아직 담은 장소가 없어요. 아래에서 추천을 받아보세요.</p>
      ) : (
        <div className="item-cards">
          {items.map((item) => (
            <ItineraryItemCard
              key={item.itemId}
              item={item}
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
