import { useEffect, useMemo, useState } from 'react';
import { CustomOverlayMap, Map, Polyline, useKakaoLoader } from 'react-kakao-maps-sdk';
import { getMapRoute } from '../api/windmillApi';
import { itemStatusLevel, isIndoorPlace, STATUS_LABEL } from '../utils/statusLevel';

const JS_KEY = import.meta.env.VITE_KAKAO_JS_KEY || '';

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

function statusText(item, weather, business, crowd) {
  const parts = [];
  if (item.scheduledTime) parts.push(item.scheduledTime);
  const level = itemStatusLevel(item, { weatherAlerted: weather, businessAlerted: business, crowdAlerted: crowd });
  if (level === 'DANGER') parts.push('야외·날씨 주의');
  else if (business) parts.push('휴무·영업 주의');
  else if (crowd || level === 'WARNING') parts.push('혼잡 주의');
  else parts.push(STATUS_LABEL.NORMAL);
  return parts.join(' · ');
}

function DayRouteMapCanvas({ stops, center }) {
  const [loading, error] = useKakaoLoader({
    appkey: JS_KEY,
    libraries: ['services'],
  });
  const [selectedId, setSelectedId] = useState(null);
  const [route, setRoute] = useState(null);
  const [routeError, setRouteError] = useState(null);
  const [loadingRoute, setLoadingRoute] = useState(false);

  const stopKey = stops.map((s) => `${s.id}:${s.lat}:${s.lng}`).join('|');

  useEffect(() => {
    if (stops.length < 2) {
      setRoute(null);
      return undefined;
    }
    let cancelled = false;
    setLoadingRoute(true);
    setRouteError(null);
    getMapRoute(stops.map((s) => ({ lon: s.lng, lat: s.lat, name: s.item.placeName })))
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
    // stopKey captures coordinate/order changes without unstable array identity
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopKey]);

  const path = (route?.path || []).map((p) => ({ lat: p.lat, lng: p.lng }));
  const selected = stops.find((s) => s.id === selectedId);

  if (loading) {
    return <p className="day-route-map-hint">지도를 불러오는 중…</p>;
  }
  if (error) {
    return (
      <p className="day-route-map-hint">
        카카오맵을 불러오지 못했어요. JS 키와 Web 도메인 제한을 확인해 주세요.
      </p>
    );
  }

  return (
    <>
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
            strokeColor="#1d6b8a"
            strokeOpacity={0.85}
            strokeStyle="solid"
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
              <p>{statusText(selected.item, selected.weather, selected.business, selected.crowd)}</p>
              {selected.item.category && <em>{selected.item.category}</em>}
            </div>
          </CustomOverlayMap>
        )}
      </Map>
      <p className="day-route-map-caption">
        {loadingRoute && '도로 경로 계산 중…'}
        {!loadingRoute && route?.roadBased && route.distanceMeters != null && (
          <>
            도로 기준 약 {(route.distanceMeters / 1000).toFixed(1)}km
            {route.durationSeconds != null && (
              <> · 약 {Math.max(1, Math.round(route.durationSeconds / 60))}분</>
            )}
          </>
        )}
        {!loadingRoute && route && !route.roadBased && (route.message || routeError || '직선 연결')}
      </p>
    </>
  );
}

/**
 * 오늘 동선 카카오맵 — 접이식. 순서 마커 + 도로 폴리라인(서버 프록시) + 상태 색.
 */
export default function DayRouteMap({
  items = [],
  weatherAffectedItemIds = [],
  businessAffectedItemIds = [],
  crowdAffectedItemIds = [],
}) {
  const [open, setOpen] = useState(true);

  const weather = useMemo(() => new Set((weatherAffectedItemIds || []).map(Number)), [weatherAffectedItemIds]);
  const business = useMemo(() => new Set((businessAffectedItemIds || []).map(Number)), [businessAffectedItemIds]);
  const crowd = useMemo(() => new Set((crowdAffectedItemIds || []).map(Number)), [crowdAffectedItemIds]);

  const stops = useMemo(() => {
    return (items || [])
      .map((item, index) => {
        const coord = parseCoord(item);
        if (!coord) return null;
        const id = Number(item.itemId);
        const indoor = isIndoorPlace(item);
        const level = itemStatusLevel(item, {
          weatherAlerted: weather.has(id) && !indoor,
          businessAlerted: business.has(id),
          crowdAlerted: crowd.has(id),
        });
        return {
          item,
          index,
          id,
          ...coord,
          level,
          weather: weather.has(id) && !indoor,
          business: business.has(id),
          crowd: crowd.has(id),
        };
      })
      .filter(Boolean);
  }, [items, weather, business, crowd]);

  const center = useMemo(() => {
    if (!stops.length) return { lat: 37.5665, lng: 126.978 };
    const lat = stops.reduce((s, p) => s + p.lat, 0) / stops.length;
    const lng = stops.reduce((s, p) => s + p.lng, 0) / stops.length;
    return { lat, lng };
  }, [stops]);

  return (
    <section className="day-route-map" aria-label="오늘 동선 지도">
      <button
        type="button"
        className="day-route-map-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span>지도로 보기</span>
        <span className="day-route-map-toggle-meta">
          {stops.length > 0 ? `${stops.length}곳` : '좌표 없음'}
          {' · '}
          {open ? '접기' : '펼치기'}
        </span>
      </button>

      {open && (
        <div className="day-route-map-body">
          {!JS_KEY && (
            <p className="day-route-map-hint">
              VITE_KAKAO_JS_KEY가 없어 지도를 표시할 수 없어요. 카카오 개발자 콘솔에서 JavaScript 키를
              발급하고 Web 도메인(localhost·배포 주소)을 등록하세요.
            </p>
          )}
          {JS_KEY && stops.length === 0 && (
            <p className="day-route-map-hint">좌표가 있는 장소가 아직 없어요.</p>
          )}
          {JS_KEY && stops.length > 0 && (
            <DayRouteMapCanvas stops={stops} center={center} />
          )}
        </div>
      )}
    </section>
  );
}
