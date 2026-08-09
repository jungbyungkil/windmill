import ItineraryItemCard from './ItineraryItemCard';
import DayRouteStrip from './DayRouteStrip';
import TravelTipsCard from './TravelTipsCard';
import BaramiBubble from './BaramiBubble';
import { tipsFromTrigger, baramiCommentFromTrigger, triggerStatusLevel } from '../utils/statusLevel';

function toIdSet(ids) {
  return new Set((ids || []).map(Number));
}

export default function ItineraryList({
  items,
  affectedItemIds = [],
  weatherAffectedItemIds,
  businessAffectedItemIds,
  crowdAffectedItemIds,
  weatherAlert = false,
  trigger = null,
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
  const weatherIds = toIdSet(
    weatherAffectedItemIds?.length ? weatherAffectedItemIds : (weatherAlert ? affectedItemIds : []),
  );
  const businessIds = toIdSet(businessAffectedItemIds);
  const crowdIds = toIdSet(crowdAffectedItemIds);
  const tips = tipsFromTrigger(trigger);
  const tipLevel = triggerStatusLevel(trigger);
  const baramiComment = baramiCommentFromTrigger(trigger);

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

      {tips.length > 0 && (
        <TravelTipsCard tips={tips} level={tipLevel} />
      )}

      {items.length > 0 && (
        <DayRouteStrip
          items={items}
          weatherAffectedItemIds={[...weatherIds]}
          businessAffectedItemIds={[...businessIds]}
          crowdAffectedItemIds={[...crowdIds]}
        />
      )}

      {weatherIds.size > 0 && (
        <p className="itinerary-weather-hint">
          빨간 <strong>야외</strong> 표시는 비·폭염 영향 일정이에요. 바람개비에서 실내로 바꿔보세요.
        </p>
      )}
      {businessIds.size > 0 && (
        <p className="itinerary-business-hint">
          <strong>휴무</strong> 표시는 방문일 기준 정기휴무·영업종료예요. 대체 장소를 골라보세요.
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
          {items.map((item) => {
            const id = Number(item.itemId);
            return (
              <ItineraryItemCard
                key={item.itemId}
                item={item}
                weatherAlerted={weatherIds.has(id)}
                businessAlerted={businessIds.has(id)}
                crowdAlerted={crowdIds.has(id)}
                onUpdateTime={onUpdateTime}
                onUpdateItem={onUpdateItem}
                onTogglePin={onTogglePin}
                onDelete={onDelete}
                onOpenDocent={onOpenDocent}
              />
            );
          })}
        </div>
      )}

      {items.length > 0 && <BaramiBubble comment={baramiComment} />}
    </div>
  );
}
