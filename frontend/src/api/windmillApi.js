const BASE_URL = import.meta.env.VITE_API_URL || '/api';

async function request(path, { method = 'GET', sessionId, body, headers } = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(sessionId ? { 'X-Session-Id': sessionId } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `요청 실패 (${res.status})`);
  }
  if (res.status === 204) return null;
  return res.json();
}

function qs(params) {
  const search = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    if (Array.isArray(value)) {
      value.forEach(v => search.append(key, v));
    } else {
      search.append(key, value);
    }
  });
  const str = search.toString();
  return str ? `?${str}` : '';
}

export function getRegions() {
  return request('/regions');
}

export function createItinerary(sessionId, { signguFullCode, startDate, endDate, companionType, withPet }) {
  return request('/itineraries', {
    method: 'POST',
    sessionId,
    body: { signguFullCode, startDate, endDate, companionType, withPet },
  });
}

export function getItinerary(itineraryId) {
  return request(`/itineraries/${itineraryId}`);
}

export function addItem(itineraryId, item) {
  return request(`/itineraries/${itineraryId}/items`, { method: 'POST', body: item });
}

export function updateItem(itineraryId, itemId, patch) {
  return request(`/itineraries/${itineraryId}/items/${itemId}`, { method: 'PATCH', body: patch });
}

export function deleteItem(itineraryId, itemId) {
  return request(`/itineraries/${itineraryId}/items/${itemId}`, { method: 'DELETE' });
}

export function getTriggerStatus(itineraryId) {
  return request(`/itineraries/${itineraryId}/trigger-status`);
}

export function confirmDay(itineraryId, date, confirmed) {
  return request(`/itineraries/${itineraryId}/days/${date}`, { method: 'PATCH', body: { confirmed } });
}

/** 응답 형태: { candidates: RecommendationCandidate[], reason: "RAIN_ALTERNATIVE" | null } */
export function getAlternatives(itineraryId, { avoid, seedPlaceName } = {}) {
  return request(`/itineraries/${itineraryId}/alternatives${qs({ avoid, seedPlaceName })}`);
}

export function getAutoPlan(itineraryId, { tags, query, placeCount } = {}) {
  return request(`/itineraries/${itineraryId}/auto-plan${qs({ tags, query, placeCount })}`);
}

/** 혼잡↓ · 날씨 · 동선 최적화 스마트 일정 (핵심 플로우). date면 해당 일자만 */
export function getSmartPlan(itineraryId, { placeCount, date } = {}) {
  return request(`/itineraries/${itineraryId}/smart-plan${qs({ placeCount, date })}`);
}

export function getRecommendations({ regionCode, withPet, companionType, seedPlaceName, tags, query, excludeContentIds, originContentId, originContentTypeId } = {}) {
  return request(`/recommendations${qs({ regionCode, withPet, companionType, seedPlaceName, tags, query, excludeContentIds, originContentId, originContentTypeId })}`);
}

/** 카테고리별 장소 추천 (식당/박물관/키즈카페/카페) - 방문자(집중률) 우선 */
export function getCategoryRecommendations({ regionCode, excludeContentIds } = {}) {
  return request(`/recommendations/by-category${qs({ regionCode, excludeContentIds })}`);
}

export function getWeather(nx, ny) {
  return request(`/weather${qs({ nx, ny })}`);
}

export function getDocent(contentId, contentTypeId, lang = 'ko') {
  return request(`/docent/${contentId}${qs({ contentTypeId, lang })}`);
}

/** 동선 꼬임 자동 재배치 */
export function optimizeRoute(itineraryId, date) {
  return request(`/itineraries/${itineraryId}/optimize-route${qs({ date })}`, { method: 'POST' });
}

/** 완성 일정 공유 토큰 발급 */
export function shareItinerary(itineraryId) {
  return request(`/itineraries/${itineraryId}/share`, { method: 'POST' });
}

/** 공유 링크로 공개 일정 조회 */
export function getSharedItinerary(token) {
  return request(`/shares/${token}`);
}

/** 현재 좌표 기반 상황 요약 */
export function getSituationByLocation(lat, lon) {
  return request(`/situation${qs({ lat, lon })}`);
}

export function getSituationByRegion(signguFullCode) {
  return request(`/situation/region/${signguFullCode}`);
}

export function createTripRecord(sessionId, record) {
  return request('/trip-records', { method: 'POST', sessionId, body: record });
}

export function getRegionTripHighlights(signguFullCode) {
  return request(`/trip-records/region/${signguFullCode}/highlights`);
}

/** 첫 화면 인기 여행 기록 피드 (좋아요·클릭 순 상위 5건) */
export function getTripStoryFeed() {
  return request('/trip-records/feed');
}

export function likeTripStory(id) {
  return request(`/trip-records/${id}/like`, { method: 'POST' });
}

export function clickTripStory(id) {
  return request(`/trip-records/${id}/click`, { method: 'POST' });
}

