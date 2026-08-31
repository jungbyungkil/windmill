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

function namesCompatible(placeName, itemName) {
  const a = normalizePlaceName(placeName);
  const b = normalizePlaceName(itemName);
  if (!a || !b) return true;
  return a === b || a.includes(b) || b.includes(a);
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

  const name = place?.placeName || place?.title;
  return (itineraryItems || []).some((item) => {
    if (readContentId(item) !== id) return false;
    return namesCompatible(name, item.placeName);
  });
}

export function itineraryContentIdSet(itineraryItems = []) {
  const ids = new Set();
  (itineraryItems || []).forEach((item) => {
    const id = readContentId(item);
    if (id) ids.add(id);
  });
  return ids;
}
