/**
 * 영업 마감(close) 선택 게이트.
 * 도착 예상 ≥ 마감−버퍼(기본 60분) 이면 선택 불가.
 * TarRlte에 이동시간 필드가 없어 Haversine·기본 20분으로 추정한다.
 */

export const CLOSE_BUFFER_MINUTES = 60;
const DEFAULT_STAY_MINUTES = 75;
const DEFAULT_TRAVEL_MINUTES = 20;

export function parseHhMm(hhmm) {
  if (!hhmm || typeof hhmm !== 'string') return null;
  const parts = hhmm.trim().split(':');
  if (parts.length < 2) return null;
  const h = Number(parts[0]);
  const m = Number(parts[1]);
  if (!Number.isFinite(h) || !Number.isFinite(m) || h < 0 || h > 23 || m < 0 || m > 59) return null;
  return h * 60 + m;
}

export function formatFriendlyMinutes(totalMinutes) {
  if (totalMinutes == null || !Number.isFinite(totalMinutes)) return '';
  const h = Math.floor(((totalMinutes % (24 * 60)) + 24 * 60) % (24 * 60) / 60);
  const m = ((totalMinutes % 60) + 60) % 60;
  if (m === 0) return `${h}시`;
  return `${h}시 ${m}분`;
}

export function formatHhMmFromMinutes(totalMinutes) {
  const h = Math.floor(((totalMinutes % (24 * 60)) + 24 * 60) % (24 * 60) / 60);
  const m = ((totalMinutes % 60) + 60) % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** "09:00 ~ 17:00" 등에서 close 분 단위 추출 */
export function extractCloseMinutesFromText(useTimeText) {
  if (!useTimeText) return null;
  const m = String(useTimeText).match(/(\d{1,2}):(\d{2})\s*[~-]\s*(\d{1,2}):(\d{2})/);
  if (!m) return null;
  const h = Number(m[3]);
  const min = Number(m[4]);
  if (!Number.isFinite(h) || !Number.isFinite(min) || h > 23 || min > 59) return null;
  return h * 60 + min;
}

function haversineKm(lon1, lat1, lon2, lat2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const R = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function travelMinutesBetween(from, to) {
  const lon1 = Number(from?.mapX);
  const lat1 = Number(from?.mapY);
  const lon2 = Number(to?.mapX);
  const lat2 = Number(to?.mapY);
  if (![lon1, lat1, lon2, lat2].every(Number.isFinite) || (lon1 === 0 && lat1 === 0)) {
    return DEFAULT_TRAVEL_MINUTES;
  }
  const km = haversineKm(lon1, lat1, lon2, lat2);
  return Math.max(10, Math.min(90, Math.ceil(km * 12)));
}

/**
 * 당일 마지막 장소 기준 도착 예상(분).
 * scheduledTime이 후보에 있으면 그걸 우선.
 */
export function estimateArrivalMinutes({ dayItems = [], visitDate, candidate, now = new Date() }) {
  const scheduled = parseHhMm(candidate?.scheduledTime);
  if (scheduled != null) return scheduled;

  const sorted = [...dayItems].sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
  const last = sorted.length ? sorted[sorted.length - 1] : null;
  let cursor;
  if (last?.scheduledTime) {
    const lastStart = parseHhMm(last.scheduledTime);
    cursor = lastStart != null ? lastStart + DEFAULT_STAY_MINUTES : 9 * 60;
  } else {
    const isToday = visitDate && String(visitDate).slice(0, 10) === now.toISOString().slice(0, 10);
    // visitDate may be "YYYY-MM-DD" local — compare with local date
    const localYmd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    const today = visitDate && String(visitDate).slice(0, 10) === localYmd;
    if (today || (!visitDate && isToday)) {
      let soon = now.getHours() * 60 + now.getMinutes() + 30;
      const m = soon % 60;
      if (m === 0) {
        // keep
      } else if (m <= 30) {
        soon = soon - m + 30;
      } else {
        soon = soon - m + 60;
      }
      cursor = Math.max(9 * 60, soon);
    } else {
      cursor = 9 * 60;
    }
  }
  const travel = last ? travelMinutesBetween(last, candidate) : DEFAULT_TRAVEL_MINUTES;
  return cursor + travel;
}

/**
 * @returns {{ allowed: boolean, blocked: boolean, message?: string, closeTime?: string, latestArrivalBy?: string, estimatedArrival?: string }}
 */
export function checkClosingGate(candidate, ctx = {}) {
  let closeMin = parseHhMm(candidate?.closeTime);
  if (closeMin == null) {
    closeMin = extractCloseMinutesFromText(candidate?.useTimeText);
  }
  if (closeMin == null) {
    return { allowed: true, blocked: false };
  }
  const arrivalMin = estimateArrivalMinutes({
    dayItems: ctx.dayItems,
    visitDate: ctx.visitDate,
    candidate,
    now: ctx.now,
  });
  const latestArrivalBy = closeMin - CLOSE_BUFFER_MINUTES;
  if (arrivalMin >= latestArrivalBy) {
    return {
      allowed: false,
      blocked: true,
      closeTime: formatHhMmFromMinutes(closeMin),
      latestArrivalBy: formatHhMmFromMinutes(latestArrivalBy),
      estimatedArrival: formatHhMmFromMinutes(arrivalMin),
      message: `이 장소는 ${formatFriendlyMinutes(closeMin)}에 마감되어 선택할 수 없습니다. ${formatFriendlyMinutes(latestArrivalBy)}까지는 도착하셔야 합니다.`,
    };
  }
  return {
    allowed: true,
    blocked: false,
    closeTime: formatHhMmFromMinutes(closeMin),
    latestArrivalBy: formatHhMmFromMinutes(latestArrivalBy),
    estimatedArrival: formatHhMmFromMinutes(arrivalMin),
  };
}
