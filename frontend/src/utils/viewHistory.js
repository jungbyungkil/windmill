const KEY = 'windtrail:viewHistory';
const MAX = 50;

export function readViewHistory() {
  try {
    const list = JSON.parse(localStorage.getItem(KEY) || '[]');
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

/** 이용 히스토리 기록 - 장소/축제 조회 시점(홈페이지 링크 탭, 도슨트 열람)에 호출 */
export function recordView({ type, id, name, thumbnail }) {
  if (!id || !name) return;
  const list = readViewHistory().filter((e) => !(e.type === type && e.id === id));
  list.unshift({ type, id, name, thumbnail: thumbnail || null, viewedAt: new Date().toISOString() });
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX)));
  } catch {
    /* 저장 공간 부족 등 - 이번 조회 기록만 유실, 치명적이지 않음 */
  }
}
