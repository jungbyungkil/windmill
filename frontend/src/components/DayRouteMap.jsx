import { useEffect, useMemo, useState } from 'react';
import { CustomOverlayMap, Map, Polyline, useKakaoLoader } from 'react-kakao-maps-sdk';
import { getMapRoute, getPublicConfig } from '../api/windmillApi';
import { itemStatusLevel, isIndoorPlace, STATUS_LABEL } from '../utils/statusLevel';
import { canOpenInKakaoMap, geocodePlaceWithKakaoServices, openInKakaoMap } from '../utils/kakaoMap';

const BUILD_TIME_JS_KEY = import.meta.env.VITE_KAKAO_JS_KEY || '';

const MARKER_COLOR = {
  NORMAL: '#2f9e6a',
  WARNING: '#d97706',
  DANGER: '#dc2626',
};

function parseCoord(item) {
  const lng = Number(item.mapX);
  const lat = Number(item.mapY);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  if (lng === 0 && lat === 0) return null;
  return { lat, lng };
}

function canGeocode(item) {
  return Boolean((item?.addr1 || '').trim() || (item?.placeName || '').trim());
}

function statusText(item, weather, closedDay, hoursEnded, crowd) {
  const parts = [];
  if (item.scheduledTime) parts.push(item.scheduledTime);
  const level = itemStatusLevel(item, {
    weatherAlerted: weather,
    businessAlerted: closedDay || hoursEnded,
    crowdAlerted: crowd,
  });
  if (level === 'DANGER') parts.push('야외·날씨 주의');
  else if (closedDay) parts.push('휴무');
  else if (hoursEnded) parts.push('영업종료');
  else if (crowd || level === 'WARNING') parts.push('혼잡 주의');
  else parts.push(STATUS_LABEL.NORMAL);
  return parts.join(' · ');
}

function buildStopMeta(item, index, weather, closedDay, hoursEnded, crowd) {
  const id = Number(item.itemId);
  const indoor = isIndoorPlace(item);
  const isClosedDay = closedDay.has(id);
  const isHoursEnded = hoursEnded.has(id);
  const level = itemStatusLevel(item, {
    weatherAlerted: weather.has(id) && !indoor,
    businessAlerted: isClosedDay || isHoursEnded,
    crowdAlerted: crowd.has(id),
  });
  return {
    item,
    index,
    id,
    level,
    weather: weather.has(id) && !indoor,
    closedDay: isClosedDay,
    hoursEnded: isHoursEnded,
    crowd: crowd.has(id),
  };
}

function DayRouteMapCanvas({ draftStops, jsKey, mode }) {
  const [loading, error] = useKakaoLoader({
    appkey: jsKey,
    libraries: ['services'],
  });
  const [stops, setStops] = useState([]);
  const [resolving, setResolving] = useState(true);
  const [failedNames, setFailedNames] = useState([]);
  const [geocodedCount, setGeocodedCount] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [route, setRoute] = useState(null);
  const [routeError, setRouteError] = useState(null);
  const [loadingRoute, setLoadingRoute] = useState(false);

  const draftKey = draftStops
    .map((s) => `${s.id}:${s.item.mapX}:${s.item.mapY}:${s.item.addr1 || ''}`)
    .join('|');

  useEffect(() => {
    if (loading || error) return undefined;
    let cancelled = false;

    async function resolveStops() {
      setResolving(true);
      const resolved = [];
      const failed = [];
      let fromAddress = 0;

      for (const draft of draftStops) {
        if (cancelled) return;
        if (draft.lat != null && draft.lng != null) {
          resolved.push(draft);
          continue;
        }
        const coord = await geocodePlaceWithKakaoServices(draft.item);
        if (coord) {
          fromAddress += 1;
          resolved.push({ ...draft, ...coord, fromAddress: true });
        } else {
          failed.push(draft.item.placeName || '이름 없음');
        }
      }

      if (cancelled) return;
      resolved.sort((a, b) => a.index - b.index);
      setStops(resolved);
      setFailedNames(failed);
      setGeocodedCount(fromAddress);
      setResolving(false);
    }

    resolveStops();
    return () => {
      cancelled = true;
    };
    // draftKey encodes identity + address/coord changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, error, draftKey]);

  const stopKey = stops.map((s) => `${s.id}:${s.lat}:${s.lng}`).join('|');

  useEffect(() => {
    if (resolving || stops.length < 2) {
      setRoute(null);
      return undefined;
    }
    let cancelled = false;
    setLoadingRoute(true);
    setRouteError(null);
    getMapRoute(stops.map((s) => ({ lon: s.lng, lat: s.lat, name: s.item.placeName })), mode)
      .then((res) => {
        if (!cancelled) setRoute(res);
      })
      .catch((e) => {
        if (cancelled) return;
        setRouteError(e.message || '경로를 불러오지 못했어요');
        setRoute({
          path: stops.map((s) => ({ lat: s.lat, lng: s.lng })),
          roadBased: false,
          message: '직선으로 연결했어요.',
        });
      })
      .finally(() => {
        if (!cancelled) setLoadingRoute(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopKey, resolving, mode]);

  const center = useMemo(() => {
    if (!stops.length) return { lat: 37.5665, lng: 126.978 };
    const lat = stops.reduce((s, p) => s + p.lat, 0) / stops.length;
    const lng = stops.reduce((s, p) => s + p.lng, 0) / stops.length;
    return { lat, lng };
  }, [stops]);

  const path = (route?.path || []).map((p) => ({ lat: p.lat, lng: p.lng }));
  const selected = stops.find((s) => s.id === selectedId);

  if (loading || resolving) {
    return <p className="day-route-map-hint">지도를 불러오는 중…</p>;
  }
  if (error) {
    const host = typeof window !== 'undefined' ? window.location.hostname : '배포도메인';
    return (
      <div className="day-route-map-hint">
        <p>카카오맵 SDK 로드에 실패했어요. 키는 내려오지만, Web 도메인 제한일 가능성이 큽니다.</p>
        <p>
          카카오 개발자 콘솔 → 앱 → 플랫폼 → Web → 사이트 도메인에 아래를 등록하세요.
        </p>
        <code>{host}</code>
        <p className="day-route-map-hint-sub">
          `https://` 없이 도메인만 넣고, JavaScript 키인지(REST 키가 아닌지) 확인해 주세요.
        </p>
      </div>
    );
  }

  if (stops.length === 0) {
    return (
      <p className="day-route-map-hint">
        주소로도 위치를 찾지 못했어요. 장소명·주소를 확인해 주세요.
        {failedNames.length > 0 ? ` (${failedNames.join(', ')})` : ''}
      </p>
    );
  }

  return (
    <>
      {geocodedCount > 0 && (
        <p className="day-route-map-hint">
          좌표가 없던 {geocodedCount}곳은 주소·이름으로 위치를 찾았어요.
        </p>
      )}
      {failedNames.length > 0 && (
        <p className="day-route-map-hint">
          위치를 찾지 못한 곳: {failedNames.join(', ')}
        </p>
      )}
      <Map
        center={center}
        level={7}
        className="day-route-map-canvas"
        onClick={() => setSelectedId(null)}
      >
        {path.length >= 2 && (
          <Polyline
            path={path}
            strokeWeight={5}
            strokeColor={route?.estimated ? '#8a8f98' : '#1d6b8a'}
            strokeOpacity={0.85}
            strokeStyle={route?.estimated ? 'shortdash' : 'solid'}
          />
        )}
        {stops.map((stop) => (
          <CustomOverlayMap
            key={stop.id}
            position={{ lat: stop.lat, lng: stop.lng }}
            yAnchor={1}
            zIndex={selectedId === stop.id ? 3 : 1}
          >
            <button
              type="button"
              className={`day-route-map-pin level-${stop.level.toLowerCase()}`}
              style={{ '--pin-color': MARKER_COLOR[stop.level] || MARKER_COLOR.NORMAL }}
              onClick={(e) => {
                e.stopPropagation();
                setSelectedId(stop.id === selectedId ? null : stop.id);
              }}
              title={stop.item.placeName}
            >
              <span className="day-route-map-pin-num">{stop.index + 1}</span>
            </button>
          </CustomOverlayMap>
        ))}
        {selected && (
          <CustomOverlayMap
            position={{ lat: selected.lat, lng: selected.lng }}
            yAnchor={1.35}
            zIndex={10}
          >
            <div className="day-route-map-info">
              <strong>{selected.item.placeName}</strong>
              <p>{statusText(selected.item, selected.weather, selected.closedDay, selected.hoursEnded, selected.crowd)}</p>
              {selected.fromAddress && <em>주소 기준 위치</em>}
              {selected.item.category && !selected.fromAddress && <em>{selected.item.category}</em>}
              {canOpenInKakaoMap(selected.item) && (
                <button
                  type="button"
                  className="day-route-map-open"
                  onClick={(e) => {
                    e.stopPropagation();
                    openInKakaoMap({
                      ...selected.item,
                      mapX: String(selected.lng),
                      mapY: String(selected.lat),
                    });
                  }}
                >
                  카카오맵에서 보기
                </button>
              )}
            </div>
          </CustomOverlayMap>
        )}
      </Map>
      <p className="day-route-map-caption">
        {loadingRoute && '경로 계산 중…'}
        {!loadingRoute && route?.roadBased && route.distanceMeters != null && (
          <>
            도로 기준 약 {(route.distanceMeters / 1000).toFixed(1)}km
            {route.durationSeconds != null && (
              <> · 약 {Math.max(1, Math.round(route.durationSeconds / 60))}분</>
            )}
          </>
        )}
        {!loadingRoute && route?.estimated && route.distanceMeters != null && (
          <>
            직선거리 약 {(route.distanceMeters / 1000).toFixed(1)}km 기준 추정
            {route.durationSeconds != null && (
              <> · 약 {Math.max(1, Math.round(route.durationSeconds / 60))}분(추정)</>
            )}
          </>
        )}
        {!loadingRoute && route && !route.roadBased && !route.estimated && (route.message || routeError || '직선 연결')}
      </p>
    </>
  );
}

/**
 * 오늘 동선 카카오맵 — "지도" 탭 전용 화면 콘텐츠. 순서 마커 + 도로 폴리라인(서버 프록시) + 상태 색.
 * TourAPI 좌표가 없어도 주소/장소명으로 카카오 지오코딩해 표시한다.
 */
export default function DayRouteMap({
  items = [],
  weatherAffectedItemIds = [],
  closedDayAffectedItemIds = [],
  hoursEndedAffectedItemIds = [],
  crowdAffectedItemIds = [],
}) {
  const [jsKey, setJsKey] = useState(BUILD_TIME_JS_KEY);
  const [keyChecked, setKeyChecked] = useState(Boolean(BUILD_TIME_JS_KEY));
  const mode = 'CAR';

  useEffect(() => {
    let cancelled = false;
    if (BUILD_TIME_JS_KEY) return undefined;
    getPublicConfig()
      .then((cfg) => {
        if (cancelled) return;
        if (cfg?.kakaoJsKey) {
          setJsKey(cfg.kakaoJsKey);
        }
      })
      .finally(() => {
        if (!cancelled) setKeyChecked(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const weather = useMemo(() => new Set((weatherAffectedItemIds || []).map(Number)), [weatherAffectedItemIds]);
  const closedDay = useMemo(() => new Set((closedDayAffectedItemIds || []).map(Number)), [closedDayAffectedItemIds]);
  const hoursEnded = useMemo(() => new Set((hoursEndedAffectedItemIds || []).map(Number)), [hoursEndedAffectedItemIds]);
  const crowd = useMemo(() => new Set((crowdAffectedItemIds || []).map(Number)), [crowdAffectedItemIds]);

  const draftStops = useMemo(() => {
    return (items || []).map((item, index) => {
      const meta = buildStopMeta(item, index, weather, closedDay, hoursEnded, crowd);
      const coord = parseCoord(item);
      if (coord) return { ...meta, ...coord };
      if (!canGeocode(item)) return null;
      return meta;
    }).filter(Boolean);
  }, [items, weather, closedDay, hoursEnded, crowd]);

  return (
    <section className="day-route-map standalone" aria-label="오늘 동선 지도">
      <div className="day-route-map-body">
        {!jsKey && keyChecked && (
          <p className="day-route-map-hint">
            카카오 JS 키를 찾지 못했어요. Render 환경변수 `VITE_KAKAO_JS_KEY` 또는 `KAKAO_JS_KEY`를
            확인하고, 카카오 개발자 콘솔 Web 도메인에 배포 주소를 등록해 주세요.
          </p>
        )}
        {jsKey && draftStops.length === 0 && (
          <p className="day-route-map-hint">좌표·주소가 있는 장소가 아직 없어요.</p>
        )}
        {jsKey && draftStops.length > 0 && (
          <DayRouteMapCanvas draftStops={draftStops} jsKey={jsKey} mode={mode} />
        )}
      </div>
    </section>
  );
}
