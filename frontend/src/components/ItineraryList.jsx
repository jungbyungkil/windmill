import ItineraryItemCard from './ItineraryItemCard';
import { isIndoorPlace } from '../utils/statusLevel';

function toIdSet(ids) {
  return new Set((ids || []).map(Number));
}

/**
 * 야외(비·폭염) 영향 ID만 사용.
 * weatherAffectedItemIds가 오면 그대로(빈 배열 포함).
 * 구버전 API만 affectedItemIds로 폴백하되, 실내 장소는 제외.
 */
function resolveWeatherIds(weatherAffectedItemIds, weatherAlert, affectedItemIds, items) {
  if (Array.isArray(weatherAffectedItemIds)) {
    return weatherAffectedItemIds.filter((id) => {
      const item = items.find((i) => Number(i.itemId) === Number(id));
      return item ? !isIndoorPlace(item) : true;
    });
  }
  if (!weatherAlert) return [];
  return (affectedItemIds || []).filter((id) => {
    const item = items.find((i) => Number(i.itemId) === Number(id));
    return item ? !isIndoorPlace(item) : false;
  });
}

export default function ItineraryList({
  items,
  affectedItemIds = [],
  weatherAffectedItemIds,
  closedDayAffectedItemIds,
  hoursEndedAffectedItemIds,
  crowdAffectedItemIds,
  weatherAlert = false,
  dayLabel,
  highlightedItemId,
  onUpdateTime,
  onUpdateItem,
  onTogglePin,
  onDelete,
  onOpenDocent,
  onOpenHistory,
  onSortByTime,
  sortByTimeLoading = false,
}) {
  const weatherIdList = resolveWeatherIds(
    weatherAffectedItemIds,
    weatherAlert,
    affectedItemIds,
    items,
  );
  const weatherIds = toIdSet(weatherIdList);
  // 휴무(정기휴무 요일)·영업종료(영업시간 밖) 구분 - 신규 필드라 구버전 폴백 없이 그대로 사용
  const closedDayIds = toIdSet(closedDayAffectedItemIds);
  const hoursEndedIds = toIdSet(hoursEndedAffectedItemIds);
  const crowdIds = toIdSet(crowdAffectedItemIds);

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

      {items.length === 0 ? (
        <div className="itinerary-empty">
          <p className="empty-state">아직 담은 장소가 없어요.</p>
          <p className="itinerary-empty-hint">검색 탭에서 장소를 찾아 담을 수 있어요.</p>
        </div>
      ) : (
        <div className="item-cards">
          {items.map((item) => {
            const id = Number(item.itemId);
            const indoor = isIndoorPlace(item);
            return (
              <ItineraryItemCard
                key={item.itemId}
                item={item}
                weatherAlerted={weatherIds.has(id) && !indoor}
                closedDayAlerted={closedDayIds.has(id)}
                hoursEndedAlerted={hoursEndedIds.has(id)}
                crowdAlerted={crowdIds.has(id)}
                highlighted={highlightedItemId != null && id === Number(highlightedItemId)}
                onUpdateTime={onUpdateTime}
                onUpdateItem={onUpdateItem}
                onTogglePin={onTogglePin}
                onDelete={onDelete}
                onOpenDocent={onOpenDocent}
                onOpenHistory={onOpenHistory}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
