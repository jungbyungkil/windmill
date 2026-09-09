/**
 * 액션(동선 최적화 · 혼잡도 대안 등) 성공 직후, 폴링(TRIGGER_POLL_MS)을 기다리지 않고
 * 방금 해소한 트리거를 클라이언트에서 즉시 제거하고 핀휠 레벨을 다시 계산한다.
 *
 * 백엔드 TriggerDetectionService.buildResult의 레벨 산정을 그대로 흉내낸다:
 *  - 비 / 폭염경보(heatUrgent) / 혼잡 긴급(crowdUrgent) / 트리거 2개 이상 → DANGER
 *  - 그 외 트리거 존재 → WARNING
 *  - 이동시간 부족(travelTime) → DANGER, 동선 꼬임(routeTangle) → 한 단계 승격
 *  - 남은 트리거가 없으면 null (핀휠 🟢 순풍) — 단, 축제 제안은 레벨과 무관하므로 살려 둔다.
 */

/** 액션 힌트 → 해소되는 트리거 플래그 / 영향 항목 리스트 키 */
const RESOLVE_MAP = {
  ROUTE: {
    flags: ['routeTangleTrigger', 'travelTimeTrigger'],
    clears: ['routeTangle', 'travelTimeAffectedItemId'],
    lists: [],
  },
  CROWD: {
    flags: ['crowdTrigger', 'crowdUrgent'],
    clears: [],
    lists: ['crowdAffectedItemIds'],
  },
  HEAT: {
    flags: ['heatTrigger', 'heatUrgent'],
    clears: [],
    lists: ['weatherAffectedItemIds'],
  },
  WEATHER: {
    flags: ['weatherTrigger'],
    clears: [],
    lists: ['weatherAffectedItemIds'],
  },
  BUSINESS: {
    flags: ['closedDayTrigger', 'hoursEndedTrigger', 'businessTrigger'],
    clears: [],
    lists: ['businessAffectedItemIds', 'closedDayAffectedItemIds', 'hoursEndedAffectedItemIds'],
  },
};

/** 해소된 카테고리에 속하는 안내 문구를 triggerDetails에서 덜어낸다 (문구는 산문이라 키워드로 매칭). */
const DETAIL_KEYWORDS = {
  ROUTE: ['동선', '이동시간', '이동 시간', '재배치', '순서'],
  CROWD: ['혼잡', '붐벼', '붐빔', '여유로운'],
  HEAT: ['폭염', '기온', '더위', '그늘'],
  WEATHER: ['비 소식', '우비', '실내 코스로'],
  BUSINESS: ['휴무', '마감', '영업'],
};

function pruneDetails(details, avoidHint) {
  if (!Array.isArray(details) || details.length === 0) return details;
  const keywords = DETAIL_KEYWORDS[avoidHint] || [];
  if (keywords.length === 0) return details;
  const kept = details.filter((line) => !keywords.some((k) => String(line).includes(k)));
  return kept;
}

function anyTriggerFlag(t) {
  return Boolean(
    t.weatherTrigger || t.heatTrigger || t.crowdTrigger || t.businessTrigger
    || t.closedDayTrigger || t.hoursEndedTrigger || t.routeTangleTrigger || t.travelTimeTrigger,
  );
}

/** 백엔드와 같은 규칙으로 triggerCount / level 재계산 */
function recomputeLevel(t) {
  const business = Boolean(t.closedDayTrigger || t.hoursEndedTrigger);
  const count = (t.weatherTrigger ? 1 : 0) + (t.heatTrigger ? 1 : 0)
    + (t.crowdTrigger ? 1 : 0) + (business ? 1 : 0);

  let level = 'NORMAL';
  if (count > 0) {
    level = (t.weatherTrigger || t.heatUrgent || t.crowdUrgent || count >= 2) ? 'DANGER' : 'WARNING';
  }
  // 이동시간 부족은 곧장 DANGER, 동선 꼬임은 한 단계 승격 (TriggerDetectionService와 동일)
  if (t.travelTimeTrigger) {
    level = 'DANGER';
  } else if (t.routeTangleTrigger) {
    level = level === 'NORMAL' ? 'WARNING' : 'DANGER';
  }
  return { count, level, business };
}

/**
 * @param {object|null} trigger  현재 트리거 상태 (api.getTriggerStatus 응답)
 * @param {'ROUTE'|'CROWD'|'HEAT'|'WEATHER'|'BUSINESS'} avoidHint  방금 수행한 액션이 겨냥한 변수
 * @returns {object|null}  트리거를 덜어낸 새 객체. 남은 변수가 없으면 null(축제 제안이 있으면 축제만 담은 객체).
 */
export function resolveTrigger(trigger, avoidHint) {
  if (!trigger) return null;
  const spec = RESOLVE_MAP[avoidHint];
  if (!spec) return trigger;

  const next = { ...trigger };
  spec.flags.forEach((k) => { next[k] = false; });
  spec.clears.forEach((k) => { next[k] = null; });
  spec.lists.forEach((k) => { next[k] = []; });

  // HEAT는 비까지 겹쳐 있을 때 weatherAffectedItemIds를 통째로 비우면 안 됨 — 비가 남아 있으면 유지
  if (avoidHint === 'HEAT' && next.weatherTrigger) {
    next.weatherAffectedItemIds = trigger.weatherAffectedItemIds;
  }

  // affectedItemIds(합집합)는 남은 개별 리스트의 합으로 다시 만든다
  const union = [];
  [next.weatherAffectedItemIds, next.businessAffectedItemIds, next.crowdAffectedItemIds]
    .forEach((list) => (list || []).forEach((id) => { if (!union.includes(id)) union.push(id); }));
  next.affectedItemIds = union;

  next.triggerDetails = pruneDetails(next.triggerDetails, avoidHint);

  const { count, level } = recomputeLevel(next);
  next.triggerCount = count;
  next.level = level;

  if (!anyTriggerFlag(next)) {
    // 변수는 다 사라졌지만 축제 제안이 남아 있으면 배너 유지를 위해 최소 객체를 돌려준다
    if (Array.isArray(trigger.festivalSuggestions) && trigger.festivalSuggestions.length > 0) {
      return {
        ...next,
        level: 'NORMAL',
        triggerCount: 0,
        triggerDetails: [],
        affectedItemIds: [],
      };
    }
    return null;
  }
  return next;
}

/** 액션 종류별 표시 라벨 (토스트·낙관적 문구 공통) */
export const ACTION_COPY = {
  ROUTE: {
    optimistic: '동선을 정리하고 있어요…',
    toast: '동선이 최적화됐어요 🍃',
    fail: '동선 정리에 실패했어요, 다시 시도해주세요',
  },
  CROWD: {
    optimistic: '한산한 곳으로 바꾸고 있어요…',
    toast: '혼잡도 대안이 적용됐어요 🍃',
    fail: '적용에 실패했어요, 다시 시도해주세요',
  },
  HEAT: {
    optimistic: '실내로 바꾸고 있어요…',
    toast: '실내 대안이 적용됐어요 🍃',
    fail: '적용에 실패했어요, 다시 시도해주세요',
  },
  WEATHER: {
    optimistic: '실내로 바꾸고 있어요…',
    toast: '실내 대안이 적용됐어요 🍃',
    fail: '적용에 실패했어요, 다시 시도해주세요',
  },
  BUSINESS: {
    optimistic: '다른 장소로 바꾸고 있어요…',
    toast: '대체 장소가 적용됐어요 🍃',
    fail: '적용에 실패했어요, 다시 시도해주세요',
  },
};
