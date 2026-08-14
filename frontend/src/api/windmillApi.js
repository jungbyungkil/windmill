const BASE_URL = import.meta.env.VITE_API_URL || '/api';
let publicConfigCache = null;

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
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // JSON이 아닌 에러 본문 (예: 서버 기본 에러 페이지) - text만 사용
    }
    const err = new Error((data && data.message) || text || `요청 실패 (${res.status})`);
    err.status = res.status;
    err.data = data;
    throw err;
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

/** 런타임 공개 설정 (민감정보 제외) */
export async function getPublicConfig() {
  if (publicConfigCache) return publicConfigCache;
  publicConfigCache = request('/public-config')
    .catch(() => ({}));
  return publicConfigCache;
}

export function createItinerary(sessionId, { signguFullCode, startDate, endDate, companionType, withPet, strollerFriendly, accessibleFriendly, adultAgeGroup, childAges, force }) {
  return request('/itineraries', {
    method: 'POST',
    sessionId,
    body: { signguFullCode, startDate, endDate, companionType, withPet, strollerFriendly, accessibleFriendly, adultAgeGroup, childAges, force },
  });
}

export function getItinerary(itineraryId) {
  return request(`/itineraries/${itineraryId}`);
}

/** 세션의 미완료 당일치기 목록 (진행 중 → 이어하기) */
export function getOngoingItineraries(sessionId) {
  return request('/itineraries/ongoing', { sessionId });
}

/** GNB "내 여행 관리" 전체 목록 - status(ACTIVE|ENDED) 필터, limit(기본 50) */
export function listItineraries(sessionId, { status, limit } = {}) {
  return request(`/itineraries${qs({ status, limit })}`, { sessionId });
}

/** GNB "여행 기록" - 완료한 당일치기를 여행일 최신순으로 조회 */
export function listTripRecords(sessionId, { limit } = {}) {
  return request(`/trip-records${qs({ limit })}`, { sessionId });
}

/** GNB "내 여행 관리" 정리 - 마무리 기록까지 함께 삭제됨(백엔드) */
export function deleteItinerary(itineraryId) {
  return request(`/itineraries/${itineraryId}`, { method: 'DELETE' });
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

/** origin이 있으면 "다음 장소까지 이동시간" 트리거도 함께 판정됨 (위치 권한 없으면 생략) */
export function getTriggerStatus(itineraryId, origin) {
  return request(`/itineraries/${itineraryId}/trigger-status${qs({
    originLon: origin?.lon,
    originLat: origin?.lat,
  })}`);
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

/**
 * 혼잡↓ · 날씨 · 동선 최적화 스마트 일정 (핵심 플로우). date면 해당 일자만.
 * standard=true면 "당일치기 시작하기" 전용 - 아침 일정/점심 식사/오후 일정/저녁 식사 4단계를
 * 현재 시각과 무관하게 무조건 채운 표준 하루 계획을 받는다.
 */
export function getSmartPlan(itineraryId, { placeCount, date, standard } = {}) {
  return request(`/itineraries/${itineraryId}/smart-plan${qs({ placeCount, date, standard })}`);
}

export function getRecommendations({ regionCode, withPet, strollerFriendly, accessibleFriendly, companionType, adultAgeGroup, childAges, seedPlaceName, tags, query, excludeContentIds, originContentId, originContentTypeId } = {}) {
  return request(`/recommendations${qs({ regionCode, withPet, strollerFriendly, accessibleFriendly, companionType, adultAgeGroup, childAges, seedPlaceName, tags, query, excludeContentIds, originContentId, originContentTypeId })}`);
}

/** 가고 싶은 곳이 정해진 사용자용 - 장소명으로 직접 검색(취향 추천이 아닌 정확한 이름 매칭). 앵커 등록에도 재사용 */
export function searchPlacesByName({ regionCode, query } = {}) {
  return request(`/recommendations/search${qs({ regionCode, query })}`);
}

/**
 * 앵커(고정 일정) 등록 - 예: DDP 19:00 공연처럼 시각이 정해진 장소를 기준점으로 그 앞뒤
 * 빈 시간대(점심·가벼운 도보 관광 / 저녁 식사·카페)를 채운 초안을 받는다. anchorTime: "HH:mm"
 */
export function getAnchorPlan(itineraryId, { anchor, anchorTime } = {}) {
  return request(`/itineraries/${itineraryId}/anchor-plan`, {
    method: 'POST',
    body: { anchor, anchorTime },
  });
}

/** 카테고리별 장소 추천 (식당/박물관/키즈카페/카페) - 방문자(집중률) 우선 */
export function getCategoryRecommendations({ regionCode, excludeContentIds } = {}) {
  return request(`/recommendations/by-category${qs({ regionCode, excludeContentIds })}`);
}

export function getWeather(nx, ny) {
  return request(`/weather${qs({ nx, ny })}`);
}

/** 중기예보 3~10일 전망 */
export function getMidWeather(signguFullCode) {
  return request(`/weather/mid${qs({ signguFullCode })}`);
}

export function getDocent(contentId, contentTypeId, lang = 'ko') {
  return request(`/docent/${contentId}${qs({ contentTypeId, lang })}`);
}

/** 카카오 길찾기 프록시 — 방문 순서 좌표 → 경로 폴리라인. mode: CAR(기본)|WALK|TRANSIT */
export function getMapRoute(points, mode) {
  return request('/map/route', {
    method: 'POST',
    body: { points, mode },
  });
}

/** 동선 최단 재배치 (optional GPS originLon/Lat = WGS84) */
export function optimizeRoute(itineraryId, date, origin) {
  const params = { date };
  if (origin?.lon != null && origin?.lat != null) {
    params.originLon = origin.lon;
    params.originLat = origin.lat;
  }
  return request(`/itineraries/${itineraryId}/optimize-route${qs(params)}`, { method: 'POST' });
}

/** 방문 시각 순으로 일정 재정렬 (시각 값은 유지) */
export function sortItineraryByTime(itineraryId, date) {
  return request(`/itineraries/${itineraryId}/sort-by-time${qs({ date })}`, { method: 'POST' });
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

/** 여행 기록(일기) 상세 - 작성한 세션만 조회 가능 */
export function getTripRecordDetail(id, sessionId) {
  return request(`/trip-records/${id}`, { sessionId });
}

/** 여행 기록(일기) 수정 - 한줄 의견/장소별 태깅·메모를 나중에 고칠 때 */
export function updateTripRecord(id, sessionId, patch) {
  return request(`/trip-records/${id}`, { method: 'PATCH', sessionId, body: patch });
}

export function getRegionTripHighlights(signguFullCode) {
  return request(`/trip-records/region/${signguFullCode}/highlights`);
}

/** 첫 화면 인기 여행 기록 피드 (좋아요·클릭 순 상위 5건) */
export function getTripStoryFeed(signguFullCode) {
  return request(`/trip-records/feed${qs({ signguFullCode })}`);
}

export function likeTripStory(id) {
  return request(`/trip-records/${id}/like`, { method: 'POST' });
}

export function clickTripStory(id) {
  return request(`/trip-records/${id}/click`, { method: 'POST' });
}

/** 웹 푸시 FCM 토큰 등록 */
export function registerPush(sessionId, { fcmToken, itineraryId } = {}) {
  return request('/push/register', {
    method: 'POST',
    sessionId,
    body: { fcmToken, itineraryId },
  });
}

/** 추천 기록 일정을 그대로 복제해 당일치기 시작 */
export function startFromTripRecord(sessionId, id, { startDate }) {
  return request(`/trip-records/${id}/start`, {
    method: 'POST',
    sessionId,
    body: { startDate },
  });
}

