import { itemStatusLevel } from '../utils/statusLevel';

/**
 * 당일치기 동선 스트립 — 지도 루트 겹침 레이아웃의 경량 버전.
 * 장소 순서·상태 색을 한 줄로 보여 연관 체인 추천 UI 참고용으로도 쓴다.
 */
export default function DayRouteStrip({ items = [], affectedItemIds = [] }) {
  if (!items.length) return null;
  const affected = new Set((affectedItemIds || []).map(Number));

  return (
    <section className="day-route-strip" aria-label="오늘 동선 미리보기">
      <div className="day-route-strip-head">
        <span className="day-route-chip">오늘 동선</span>
        <span className="day-route-sub">시간 순 · 상태 색으로 표시</span>
      </div>
      <ol className="day-route-track">
        {items.map((item, index) => {
          const level = itemStatusLevel(item, {
            alerted: affected.has(Number(item.itemId)),
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
