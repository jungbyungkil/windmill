import { useEffect, useMemo, useRef, useState } from 'react';
import { CustomOverlayMap, Map, Polyline, useKakaoLoader } from 'react-kakao-maps-sdk';
import { getMapRoute, getPublicConfig, searchNearbyPlaces } from '../api/windmillApi';
import { TOUR_FOOD_CONTENT_TYPE_ID } from '../constants';
import { itemStatusLevel, isIndoorPlace, STATUS_LABEL } from '../utils/statusLevel';
import { canOpenInKakaoMap, geocodePlaceWithKakaoServices, openInKakaoMap } from '../utils/kakaoMap';
import { hoursPhaseForPlace, HOURS_PHASE_LABEL } from '../utils/hoursPhase';
import { isPlaceInItinerary, readContentId } from '../utils/itineraryMembership';
import MapPlaceCard from './MapPlaceCard';

const BUILD_TIME_JS_KEY = import.meta.env.VITE_KAKAO_JS_KEY || '';
const DEFAULT_CENTER = { lat: 37.5665, lng: 126.978 };
const SEARCH_PIN = '#2563eb';
const SEARCH_IN_ITINERARY_PIN = '#0f766e';

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

function readMapCenter(map) {
  if (!map?.getCenter) return null;
  const c = map.getCenter();
  const lat = Number(c.getLat());
  const lng = Number(c.getLng());
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  return { lat, lng };
}

function DayRouteMapCanvas({
  draftStops,
  jsKey,
  mode,
  itineraryItems = [],
  onAddPlace,
  onRemovePlace,
  busyContentId,
}) {
  const [loading, error] = useKakaoLoader({
    appkey: jsKey,
    libraries: ['services'],
  });
  const mapRef = useRef(null);
  const userMovedRef = useRef(false);
  const [stops, setStops] = useState([]);
  const [resolving, setResolving] = useState(true);
  const [failedNames, setFailedNames] = useState([]);
  const [geocodedCount, setGeocodedCount] = useState(0);
  const [selectedStopId, setSelectedStopId] = useState(null);
  const [selectedPlaceId, setSelectedPlaceId] = useState(null);
  const [route, setRoute] = useState(null);
  const [routeError, setRouteError] = useState(null);
  const [loadingRoute, setLoadingRoute] = useState(false);
  const [center, setCenter] = useState(DEFAULT_CENTER);
  const [radius, setRadius] = useState(1000);
  const [foodOnly, setFoodOnly] = useState(false);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState(null);
  const [nearby, setNearby] = useState([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [movedSinceSearch, setMovedSinceSearch] = useState(false);
  const [optimisticAdded, setOptimisticAdded] = useState(() => new Set());
  const [optimisticRemoved, setOptimisticRemoved] = useState(() => new Set());

  const draftKey = draftStops
    .map((s) => `${s.id}:${s.item.mapX}:${s.item.mapY}:${s.item.addr1 || ''}`)
    .join('|');

  const itineraryContentIds = useMemo(() => {
    const ids = new Set();
    (itineraryItems || []).forEach((item) => {
      const id = readContentId(item);
      if (id) ids.add(id);
    });
    return ids;
  }, [itineraryItems]);

  useEffect(() => {
    setOptimisticAdded((prev) => {
      const next = new Set(prev);
      let changed = false;
      prev.forEach((id) => {
        if (itineraryContentIds.has(id)) {
          next.delete(id);
          changed = true;
        }
      });
      return changed ? next : prev;
    });
    setOptimisticRemoved((prev) => {
      const next = new Set(prev);
      let changed = false;
      prev.forEach((id) => {
        if (!itineraryContentIds.has(id)) {
          next.delete(id);
          changed = true;
        }
      });
      return changed ? next : prev;
    });
  }, [itineraryContentIds]);

  function isInItinerary(placeOrId) {
    if (placeOrId != null && typeof placeOrId === 'object') {
      return isPlaceInItinerary(placeOrId, itineraryItems, optimisticAdded, optimisticRemoved);
    }
    const id = readContentId(placeOrId);
    if (!id) return false;
    if (optimisticRemoved.has(id)) return false;
    if (optimisticAdded.has(id)) return true;
    return itineraryContentIds.has(id);
  }

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
      if (!userMovedRef.current && resolved.length > 0) {
        const lat = resolved.reduce((s, p) => s + p.lat, 0) / resolved.length;
        const lng = resolved.reduce((s, p) => s + p.lng, 0) / resolved.length;
        setCenter({ lat, lng });
      }
    }

    resolveStops();
    return () => {
      cancelled = true;
    };
    // draftKey encodes identity + address/coord changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, error, draftKey]);

  useEffect(() => {
    if (loading || error || draftStops.length > 0) return undefined;
    if (!navigator.geolocation) {
      setResolving(false);
      return undefined;
    }
    let cancelled = false;
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        if (cancelled || userMovedRef.current) return;
        setCenter({ lat: pos.coords.latitude, lng: pos.coords.longitude });
        setResolving(false);
      },
      () => {
        if (!cancelled) setResolving(false);
      },
      { enableHighAccuracy: false, timeout: 4000, maximumAge: 2 * 60 * 1000 },
    );
    return () => {
      cancelled = true;
    };
  }, [loading, error, draftStops.length]);

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

  const itineraryContentOnMap = useMemo(() => {
    const ids = new Set();
    stops.forEach((s) => {
      const id = readContentId(s.item);
      if (id) ids.add(id);
    });
    return ids;
  }, [stops]);

  const searchMarkers = useMemo(() => {
    return (nearby || []).flatMap((place) => {
      const coord = parseCoord(place);
      if (!coord) return [];
      const id = readContentId(place);
      const added = isInItinerary(place);
      if (id && itineraryContentOnMap.has(id) && added) return [];
      return [{ ...place, ...coord, contentKey: id, inItinerary: added }];
    });
    // optimistic sets change inItinerary without nearby identity changing
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nearby, itineraryContentOnMap, optimisticAdded, optimisticRemoved, itineraryContentIds]);

  const selectedStop = stops.find((s) => s.id === selectedStopId);
  const selectedPlace = (nearby || []).find((p) => readContentId(p) === selectedPlaceId)
    || searchMarkers.find((p) => p.contentKey === selectedPlaceId);

  function markUserMoved() {
    userMovedRef.current = true;
    if (hasSearched) setMovedSinceSearch(true);
  }

  async function handleResearch() {
    const fromMap = readMapCenter(mapRef.current);
    const target = fromMap || center;
    if (!target) return;
    setSearching(true);
    setSearchError(null);
    try {
      const results = await searchNearbyPlaces({
        mapX: target.lng,
        mapY: target.lat,
        radius,
        contentTypeId: foodOnly ? TOUR_FOOD_CONTENT_TYPE_ID : undefined,
      });
      setNearby(Array.isArray(results) ? results : []);
      setHasSearched(true);
      setMovedSinceSearch(false);
      userMovedRef.current = true;
      setSelectedStopId(null);
      setSelectedPlaceId(null);
    } catch (e) {
      setSearchError(e.message || '주변 장소를 불러오지 못했어요');
      setNearby([]);
    } finally {
      setSearching(false);
    }
  }

  function handleClearSearch() {
    setNearby([]);
    setHasSearched(false);
    setSearchError(null);
    setSelectedPlaceId(null);
    setMovedSinceSearch(false);
  }

  async function handleAdd(place) {
    const id = readContentId(place);
    if (!id) return;
    setOptimisticAdded((prev) => new Set(prev).add(id));
    setOptimisticRemoved((prev) => {
      if (!prev.has(id)) return prev;
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
    try {
      await onAddPlace?.(place);
    } catch {
      setOptimisticAdded((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  }

  async function handleRemove(place) {
    const id = readContentId(place);
    if (!id) return;
    setOptimisticRemoved((prev) => new Set(prev).add(id));
    try {
      await onRemovePlace?.(id);
    } catch {
      setOptimisticRemoved((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  }

  if (loading || (resolving && draftStops.length > 0 && stops.length === 0)) {
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

  const path = (route?.path || []).map((p) => ({ lat: p.lat, lng: p.lng }));
  const selectedHours = selectedPlace ? hoursPhaseForPlace(selectedPlace) : 'UNKNOWN';
  const showClearSearch = hasSearched || nearby.length > 0;

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
      <div className="day-route-map-stage">
        <Map
          center={center}
          isPanto
          level={7}
          className="day-route-map-canvas"
          onCreate={(map) => {
            mapRef.current = map;
          }}
          onDragEnd={(map) => {
            mapRef.current = map;
            markUserMoved();
          }}
          onZoomChanged={(map) => {
            mapRef.current = map;
            markUserMoved();
          }}
          onClick={() => {
            setSelectedStopId(null);
            setSelectedPlaceId(null);
          }}
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
              key={`stop-${stop.id}`}
              position={{ lat: stop.lat, lng: stop.lng }}
              yAnchor={1}
              zIndex={selectedStopId === stop.id ? 4 : 2}
            >
              <button
                type="button"
                className={`day-route-map-pin level-${stop.level.toLowerCase()}`}
                style={{ '--pin-color': MARKER_COLOR[stop.level] || MARKER_COLOR.NORMAL }}
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedPlaceId(null);
                  setSelectedStopId(stop.id === selectedStopId ? null : stop.id);
                }}
                title={stop.item.placeName}
              >
                <span className="day-route-map-pin-num">{stop.index + 1}</span>
              </button>
            </CustomOverlayMap>
          ))}
          {searchMarkers.map((place) => (
            <CustomOverlayMap
              key={`search-${place.contentKey}`}
              position={{ lat: place.lat, lng: place.lng }}
              yAnchor={1}
              zIndex={selectedPlaceId === place.contentKey ? 5 : 1}
            >
              <button
                type="button"
                className={`day-route-map-pin search-pin${place.inItinerary ? ' in-itinerary' : ''}`}
                style={{ '--pin-color': place.inItinerary ? SEARCH_IN_ITINERARY_PIN : SEARCH_PIN }}
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedStopId(null);
                  setSelectedPlaceId(place.contentKey === selectedPlaceId ? null : place.contentKey);
                }}
                title={place.placeName}
              >
                <span className="day-route-map-pin-num">{place.inItinerary ? '✓' : '+'}</span>
              </button>
            </CustomOverlayMap>
          ))}
          {selectedStop && (
            <CustomOverlayMap
              position={{ lat: selectedStop.lat, lng: selectedStop.lng }}
              yAnchor={1.35}
              zIndex={10}
            >
              <div className="day-route-map-info">
                <strong>{selectedStop.item.placeName}</strong>
                <p>{statusText(selectedStop.item, selectedStop.weather, selectedStop.closedDay, selectedStop.hoursEnded, selectedStop.crowd)}</p>
                {selectedStop.fromAddress && <em>주소 기준 위치</em>}
                {selectedStop.item.category && !selectedStop.fromAddress && <em>{selectedStop.item.category}</em>}
                {canOpenInKakaoMap(selectedStop.item) && (
                  <button
                    type="button"
                    className="day-route-map-open"
                    onClick={(e) => {
                      e.stopPropagation();
                      openInKakaoMap({
                        ...selectedStop.item,
                        mapX: String(selectedStop.lng),
                        mapY: String(selectedStop.lat),
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
        <div className="day-route-map-toolbar">
          <div className="day-route-map-filters" role="group" aria-label="검색 옵션">
            <button
              type="button"
              className={`map-filter-chip${radius === 500 ? ' on' : ''}`}
              onClick={() => setRadius(500)}
            >
              500m
            </button>
            <button
              type="button"
              className={`map-filter-chip${radius === 1000 ? ' on' : ''}`}
              onClick={() => setRadius(1000)}
            >
              1km
            </button>
            <button
              type="button"
              className={`map-filter-chip${foodOnly ? ' on' : ''}`}
              onClick={() => setFoodOnly((v) => !v)}
              aria-pressed={foodOnly}
            >
              음식점만
            </button>
          </div>
          <div className="day-route-map-actions">
            {showClearSearch && (
              <button
                type="button"
                className="map-clear-btn"
                onClick={handleClearSearch}
              >
                마커 지우기
              </button>
            )}
            <button
              type="button"
              className={`map-research-btn${movedSinceSearch ? ' emphasize' : ''}`}
              onClick={handleResearch}
              disabled={searching}
            >
              {searching ? '검색 중…' : '이 지역 재검색'}
            </button>
          </div>
        </div>
        {selectedPlace && (
          <div className="map-place-sheet">
            <MapPlaceCard
              place={selectedPlace}
              itineraryItems={itineraryItems}
              pendingAddedIds={optimisticAdded}
              pendingRemovedIds={optimisticRemoved}
              hoursPhase={selectedHours}
              busy={busyContentId != null && readContentId(busyContentId) === readContentId(selectedPlace)}
              onAdd={handleAdd}
              onRemove={handleRemove}
              onClose={() => setSelectedPlaceId(null)}
            />
          </div>
        )}
      </div>
      <p className="day-route-map-caption">
        지도를 옮긴 뒤 <strong>이 지역 재검색</strong>을 눌러야 주변 장소가 갱신돼요.
        파란 마커는 <strong>마커 지우기</strong>로 없애고 오늘 동선만 볼 수 있어요.
        {loadingRoute && ' 경로 계산 중…'}
        {!loadingRoute && route?.roadBased && route.distanceMeters != null && (
          <>
            {' '}도로 기준 약 {(route.distanceMeters / 1000).toFixed(1)}km
            {route.durationSeconds != null && (
              <> · 약 {Math.max(1, Math.round(route.durationSeconds / 60))}분</>
            )}
          </>
        )}
        {!loadingRoute && route?.estimated && route.distanceMeters != null && (
          <>
            {' '}직선거리 약 {(route.distanceMeters / 1000).toFixed(1)}km 기준 추정
            {route.durationSeconds != null && (
              <> · 약 {Math.max(1, Math.round(route.durationSeconds / 60))}분(추정)</>
            )}
          </>
        )}
        {!loadingRoute && route && !route.roadBased && !route.estimated && (route.message || routeError || '')}
      </p>
      {searchError && <p className="day-route-map-hint">{searchError}</p>}
      {hasSearched && nearby.length === 0 && !searching && !searchError && (
        <p className="day-route-map-hint">이 반경에서 장소를 찾지 못했어요. 지도를 옮기거나 반경을 넓혀 보세요.</p>
      )}
      {nearby.length > 0 && (
        <div className="map-nearby-list" aria-label="검색된 장소">
          <p className="map-nearby-list-label">
            주변 {nearby.length}곳
            {nearby.length >= 8 ? ' · 겹치면 목록에서 고르세요' : ''}
          </p>
          <ul>
            {nearby.map((place) => {
              const id = readContentId(place);
              const added = isInItinerary(place);
              const phase = hoursPhaseForPlace(place, { inItinerary: added });
              return (
                <li key={id || place.placeName}>
                  <button
                    type="button"
                    className={`map-nearby-row${selectedPlaceId === id ? ' on' : ''}${added ? ' added' : ''}`}
                    onClick={() => {
                      setSelectedStopId(null);
                      setSelectedPlaceId(id);
                      const coord = parseCoord(place);
                      if (coord) setCenter(coord);
                    }}
                  >
                    <span className="map-nearby-row-name">{place.placeName}</span>
                    <span className="map-nearby-row-meta">
                      {place.category || '장소'}
                      {place.dist != null ? ` · ${place.dist}m` : ''}
                      {added ? ' · 담김' : ''}
                    </span>
                    <span className={`map-nearby-row-hours phase-${String(phase).toLowerCase().replace('_', '-')}`}>
                      {phase === 'UNKNOWN' ? '' : HOURS_PHASE_LABEL[phase]}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </>
  );
}

/**
 * 오늘 동선 카카오맵 — 일정 화면의 지도 구간.
 * 순서 마커 + 도로 폴리라인 + "이 지역 재검색"으로 주변 장소를 일정에 담기.
 */
export default function DayRouteMap({
  items = [],
  weatherAffectedItemIds = [],
  closedDayAffectedItemIds = [],
  hoursEndedAffectedItemIds = [],
  crowdAffectedItemIds = [],
  onAddPlace,
  onRemovePlace,
  busyContentId,
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
        {jsKey && (
          <DayRouteMapCanvas
            draftStops={draftStops}
            jsKey={jsKey}
            mode={mode}
            itineraryItems={items}
            onAddPlace={onAddPlace}
            onRemovePlace={onRemovePlace}
            busyContentId={busyContentId}
          />
        )}
      </div>
    </section>
  );
}
