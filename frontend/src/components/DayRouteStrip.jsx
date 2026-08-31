import { useState } from 'react';
import { itemStatusLevel, isIndoorPlace } from '../utils/statusLevel';
import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';

/**
 * 당일치기 동선 스트립 — 장소 순서·상태 색을 한 줄로 표시.
 * 「동선 재계산」: GPS(가능 시) 시작점 + 서버에서 이동시간 TSP·시간표 재생성.
 * 첫 장소 도착 시각을 직접 지정할 수 있고(비우면 자동), 나머지는 그 뒤로 체류·이동시간만큼
 * 자연스럽게 이어 붙는다. 장소 탭 → 카카오맵 좌표 이동.
 */
export default function DayRouteStrip({
  items = [],
  weatherAffectedItemIds = [],
  businessAffectedItemIds = [],
  crowdAffectedItemIds = [],
  onOptimizeFromGps,
  gpsOptimizing = false,
  onSuggestRoute,
  suggestLoading = false,
  suggestHighlight = false,
}) {
  const [startTime, setStartTime] = useState('');

  if (!items.length) return null;
  const weather = new Set((weatherAffectedItemIds || []).map(Number));
  const business = new Set((businessAffectedItemIds || []).map(Number));
  const crowd = new Set((crowdAffectedItemIds || []).map(Number));

  const busy = gpsOptimizing || suggestLoading;

  return (
    <section className={`day-route-strip${gpsOptimizing ? ' is-reordering' : ''}`} aria-label="오늘 동선 미리보기">
      <div className="day-route-strip-head">
        <span className="day-route-chip">오늘 동선</span>
        <span className="day-route-sub">탭하면 카카오맵 · 상태 색으로 표시</span>
        {(onOptimizeFromGps || onSuggestRoute) && items.length >= 2 && (
          <div className="day-route-recalc-controls">
            {onOptimizeFromGps && (
              <>
                <input
                  type="time"
                  className="day-route-start-time"
                  value={startTime}
                  onChange={(e) => setStartTime(e.target.value)}
                  disabled={busy}
                  aria-label="첫 장소 도착 시각 지정(선택)"
                  title="첫 장소 도착 시각을 직접 정해보세요. 비워두면 자동으로 잡아요."
                />
                <button
                  type="button"
                  className="day-route-gps-btn"
                  onClick={() => onOptimizeFromGps(startTime || undefined)}
                  disabled={busy}
                  title="이동시간·체류를 반영해 방문 순서와 시간표를 다시 잡습니다"
                  aria-label="동선 재계산"
                >
                  {gpsOptimizing ? '재계산…' : '동선 재계산'}
                </button>
              </>
            )}
            {onSuggestRoute && (
              <button
                type="button"
                className={`day-route-suggest-btn${suggestHighlight ? ' is-highlight' : ''}`}
                onClick={onSuggestRoute}
                disabled={busy}
                title="지금 위치와 시각으로 순서를 제안합니다. 확인해야 일정에 반영돼요."
                aria-label="이 순서 어때요"
              >
                {suggestLoading ? '제안 중…' : '이 순서 어때요?'}
              </button>
            )}
          </div>
        )}
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
          const openable = canOpenInKakaoMap(item);
          return (
            <li key={item.itemId} className={`day-route-stop level-${level}`}>
              {index > 0 && <span className="day-route-line" aria-hidden="true" />}
              <button
                type="button"
                className={`day-route-stop-btn${openable ? '' : ' is-static'}`}
                onClick={() => openable && openInKakaoMap(item)}
                disabled={!openable}
                title={openable ? '카카오맵에서 보기' : item.placeName}
              >
                <span className="day-route-node">
                  <span className="day-route-order">{index + 1}</span>
                </span>
                <span className="day-route-label">
                  <strong>{item.scheduledTime || '--:--'}</strong>
                  <em>{item.placeName}</em>
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
