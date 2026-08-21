/**
 * 같은 날 이미 예정된 다른 일정과 시간대가 겹치는지 검사(마감시간 게이트와 독립적인 원인).
 * 서버(TimeConflictGate)와 동일한 기본 체류시간(75분) 가정을 클라이언트에서 먼저 반영해
 * 굳이 API 왕복 없이 알려준다 - checkClosingGate와 같은 자리에서 씀.
 */
import { parseHhMm, formatFriendlyMinutes, formatHhMmFromMinutes } from './closingTime';

const STAY_MINUTES = 75;

/**
 * @param {string} candidateScheduledTime - "HH:mm"
 * @param {Array} dayItems - 같은 날짜의 기존 일정 목록 (itemId/placeName/scheduledTime)
 * @param {string|number|null} [excludeItemId] - 자기 자신(기존 항목 시간 수정 시) 제외
 * @returns {{ allowed: boolean, blocked: boolean, message?: string, conflictingItemId?, conflictingPlaceName?, conflictingTime? }}
 */
export function checkTimeConflict(candidateScheduledTime, dayItems = [], excludeItemId = null) {
  const start = parseHhMm(candidateScheduledTime);
  if (start == null) {
    return { allowed: true, blocked: false };
  }
  const end = start + STAY_MINUTES;
  for (const item of dayItems) {
    if (excludeItemId != null && item.itemId === excludeItemId) continue;
    const oStart = parseHhMm(item.scheduledTime);
    if (oStart == null) continue;
    const oEnd = oStart + STAY_MINUTES;
    if (start < oEnd && oStart < end) {
      return {
        allowed: false,
        blocked: true,
        conflictingItemId: item.itemId,
        conflictingPlaceName: item.placeName,
        conflictingTime: formatHhMmFromMinutes(oStart),
        message: `${formatFriendlyMinutes(oStart)}에 이미 다른 일정(${item.placeName})이 있어요.`,
      };
    }
  }
  return { allowed: true, blocked: false };
}
