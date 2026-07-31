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

export function createItinerary(sessionId, destination) {
  return request(`/itineraries${qs({ destination })}`, { method: 'POST', sessionId });
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

export function getAlternatives(itineraryId, { avoid, seedPlaceName } = {}) {
  return request(`/itineraries/${itineraryId}/alternatives${qs({ avoid, seedPlaceName })}`);
}

export function getAutoPlan(itineraryId, { tags, query, placeCount } = {}) {
  return request(`/itineraries/${itineraryId}/auto-plan${qs({ tags, query, placeCount })}`);
}

export function getRecommendations({ seedPlaceName, tags, query, excludeContentIds } = {}) {
  return request(`/recommendations${qs({ seedPlaceName, tags, query, excludeContentIds })}`);
}

export function getWeather(nx, ny) {
  return request(`/weather${qs({ nx, ny })}`);
}

export function getDocent(contentId, contentTypeId, lang = 'ko') {
  return request(`/docent/${contentId}${qs({ contentTypeId, lang })}`);
}

export function createTripRecord(sessionId, record) {
  return request('/trip-records', { method: 'POST', sessionId, body: record });
}
