import { itemStatusLevel, isIndoorPlace } from '../utils/statusLevel';

/**
 * 당일치기 동선 스트립 — 장소 순서·상태 색을 한 줄로 표시.
 */
export default function DayRouteStrip({
  items = [],
  weatherAffectedItemIds = [],
  businessAffectedItemIds = [],
  crowdAffectedItemIds = [],
}) {
  if (!items.length) return null;
  const weather = new Set((weatherAffectedItemIds || []).map(Number));
  const business = new Set((businessAffectedItemIds || []).map(Number));
  const crowd = new Set((crowdAffectedItemIds || []).map(Number));

  return (
    <section className="day-route-strip" aria-label="오늘 동선 미리보기">
      <div className="day-route-strip-head">
        <span className="day-route-chip">오늘 동선</span>
        <span className="day-route-sub">시간 순 · 상태 색으로 표시</span>
      </div>
      <ol className="day-route-track">
        {items.map((item, index) => {
          const id = Number(item.itemId);
          const indoor = isIndoorPlace(item);
          const level = itemStatusLevel(item, {
            weatherAlerted: weather.has(id) && !indoor,
            businessAlerted: business.has(id),
            crowdAlerted: crowd.has(id),
          }).toLowerCase();
          return (
            <li key={item.itemId} className={`day-route-stop level-${level}`}>
              {index > 0 && <span className="day-route-line" aria-hidden="true" />}
              <span className="day-route-node" title={item.placeName}>
                <span className="day-route-order">{index + 1}</span>
              </span>
              <span className="day-route-label">
                <strong>{item.scheduledTime || '--:--'}</strong>
                <em>{item.placeName}</em>
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
