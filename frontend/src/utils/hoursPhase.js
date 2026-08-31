/**
 * 장소 카드 영업 3단계: 영업 전 / 영업 중 / 영업 종료.
 * locationBasedList2에는 영업시간이 없어 UNKNOWN이 기본.
 * 일정 항목은 트리거·useTimeText 스냅샷으로 같은 3단계를 재사용한다.
 */

const TIME_RANGE = /(\d{1,2}):(\d{2})\s*[~-]\s*(\d{1,2}):(\d{2})/;

export const HOURS_PHASE = {
  BEFORE_OPEN: 'BEFORE_OPEN',
  OPEN: 'OPEN',
  CLOSED: 'CLOSED',
  CLOSED_DAY: 'CLOSED_DAY',
  UNKNOWN: 'UNKNOWN',
};

export const HOURS_PHASE_LABEL = {
  BEFORE_OPEN: '영업 전',
  OPEN: '영업 중',
  CLOSED: '영업 종료',
  CLOSED_DAY: '휴무',
  UNKNOWN: '영업정보 없음',
};

function parseRange(text) {
  if (!text) return null;
  const m = String(text).match(TIME_RANGE);
  if (!m) return null;
  const startH = Number(m[1]);
  const startM = Number(m[2]);
  const endH = Number(m[3]);
  const endM = Number(m[4]);
  if ([startH, startM, endH, endM].some((n) => !Number.isFinite(n))) return null;
  if (startH > 23 || endH > 23 || startM > 59 || endM > 59) return null;
  return { start: startH * 60 + startM, end: endH * 60 + endM };
}

function minutesNow(now = new Date()) {
  return now.getHours() * 60 + now.getMinutes();
}

export function phaseFromUseTime(useTimeText, closeTime, now = new Date()) {
  const range = parseRange(useTimeText);
  const nowMin = minutesNow(now);
  if (range) {
    const { start, end } = range;
    const overnight = end < start;
    const open = overnight ? nowMin >= start || nowMin <= end : nowMin >= start && nowMin <= end;
    if (open) return HOURS_PHASE.OPEN;
    if (!overnight && nowMin < start) return HOURS_PHASE.BEFORE_OPEN;
    if (overnight && nowMin < start && nowMin > end) return HOURS_PHASE.BEFORE_OPEN;
    return HOURS_PHASE.CLOSED;
  }
  if (closeTime && /^\d{1,2}:\d{2}$/.test(closeTime)) {
    const [h, m] = closeTime.split(':').map(Number);
    const closeMin = h * 60 + m;
    if (nowMin > closeMin) return HOURS_PHASE.CLOSED;
  }
  return null;
}

/**
 * @param {object} place search result or itinerary item
 * @param {{ closedDay?: boolean, hoursEnded?: boolean }} flags
 */
export function hoursPhaseForPlace(place, { closedDay = false, hoursEnded = false } = {}) {
  if (closedDay) return HOURS_PHASE.CLOSED_DAY;
  const apiPhase = place?.hoursPhase;
  if (apiPhase && apiPhase !== HOURS_PHASE.UNKNOWN) {
    return apiPhase;
  }
  const fromText = phaseFromUseTime(place?.useTimeText, place?.closeTime);
  if (hoursEnded) {
    if (fromText === HOURS_PHASE.BEFORE_OPEN) return HOURS_PHASE.BEFORE_OPEN;
    return HOURS_PHASE.CLOSED;
  }
  if (fromText) return fromText;
  return HOURS_PHASE.UNKNOWN;
}
