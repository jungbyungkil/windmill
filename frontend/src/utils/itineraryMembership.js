/**
 * 검색 장소가 오늘 일정에 들어 있는지 — 마커/목록/팝업이 같은 규칙을 쓴다.
 * contentId만 보고, 빈 값·영업상태 문자열 같은 truthy 값은 포함으로 치지 않는다.
 */

export function readContentId(placeOrId) {
  if (placeOrId == null) return '';
  if (typeof placeOrId === 'object') {
    const raw = placeOrId.contentId ?? placeOrId.contentid;
    return raw == null ? '' : String(raw).trim();
  }
  const id = String(placeOrId).trim();
  if (!id || id === 'undefined' || id === 'null' || id === 'UNKNOWN' || id === 'true' || id === 'false') {
    return '';
  }
  return id;
}

export function normalizePlaceName(name) {
  return String(name || '').replace(/\s+/g, '').toLowerCase();
}

/**
 * @param {object} place 검색 결과 또는 일정 항목
 * @param {object[]} itineraryItems
 * @param {Set<string>} [pendingAddedIds]
 * @param {Set<string>} [pendingRemovedIds]
 */
export function isPlaceInItinerary(place, itineraryItems = [], pendingAddedIds, pendingRemovedIds) {
  const id = readContentId(place);
  if (!id) return false;
  if (pendingRemovedIds?.has(id)) return false;
  if (pendingAddedIds?.has(id)) return true;

  // 같은 contentId면 같은 장소다. "DDP" vs "동대문디자인플라자"처럼 표기가 달라도
  // 이름으로 다시 걸러 내면 지도는 미담김, 목록은 담김으로 어긋난다.
  return (itineraryItems || []).some((item) => readContentId(item) === id);
}

export function itineraryContentIdSet(itineraryItems = []) {
  const ids = new Set();
  (itineraryItems || []).forEach((item) => {
    const id = readContentId(item);
    if (id) ids.add(id);
  });
  return ids;
}
