/** 바람따라 상태 컬러 체계: NORMAL(정상) · WARNING(주의) · DANGER(긴급) */

export const STATUS_LABEL = {
  NORMAL: '정상',
  WARNING: '주의',
  DANGER: '긴급',
};

/**
 * 일정 항목 상태.
 * 날씨·폭염 영향 → DANGER, 혼잡 높음 → WARNING, 그 외 → NORMAL
 */
export function itemStatusLevel(item, { alerted = false } = {}) {
  if (alerted) return 'DANGER';
  const crowd = item?.crowdRate;
  if (crowd != null && crowd >= 70) return 'WARNING';
  return 'NORMAL';
}

/** 트리거 전체 레벨 (API TriggerLevel) */
export function triggerStatusLevel(trigger) {
  if (!trigger?.level) return 'NORMAL';
  return String(trigger.level).toUpperCase();
}

/**
 * 트리거 → 여행 꿀팁 카드용 항목
 */
export function tipsFromTrigger(trigger) {
  if (!trigger) return [];
  const tips = [];
  if (trigger.heatTrigger) {
    tips.push({ id: 'heat', icon: '🌡️', text: '폭염이에요. 야외는 짧게, 그늘·실내 코스로 바꿔 보세요.' });
  }
  if (trigger.weatherTrigger) {
    tips.push({ id: 'rain', icon: '🌧️', text: '비 소식이에요. 우비·실내 일정을 준비해 두면 좋아요.' });
  }
  if (trigger.crowdTrigger) {
    tips.push({ id: 'crowd', icon: '👥', text: '혼잡이 예상돼요. 이른 시간이나 대안 장소로 피하면 편해요.' });
  }
  if (trigger.businessTrigger) {
    tips.push({ id: 'biz', icon: '🚫', text: '휴무·영업 종료 일정이 있어요. 열고 닫는 시간을 한 번 더 확인하세요.' });
  }
  if (trigger.routeTangleTrigger) {
    tips.push({ id: 'route', icon: '🔀', text: '동선이 꼬였어요. 자동 재배치로 이동을 줄여 보세요.' });
  }
  return tips;
}

/** 바람이 한 줄 코멘트 */
export function baramiCommentFromTrigger(trigger) {
  if (!trigger) {
    return '오늘은 순항 중이에요. 이 코스대로 다녀도 좋아요!';
  }
  if (trigger.heatTrigger) {
    return '더위가 세네요. 이 코스에서 야외만 실내로 바꾸면 훨씬 편해질 거예요.';
  }
  if (trigger.weatherTrigger) {
    return '비 소식이 있어요. 이 코스대로 가되 실내 대안을 옆에 둬 볼까요?';
  }
  if (trigger.routeTangleTrigger) {
    return '동선이 조금 꼬였어요. 순서만 다시 잡으면 대표 코스를 알차게 돌 수 있어요.';
  }
  if (trigger.crowdTrigger) {
    return '붐비는 곳이 있어요. 시간만 살짝 옮기면 여유롭게 즐길 수 있어요.';
  }
  if (trigger.businessTrigger) {
    return '쉬는 날이 끼어 있어요. 대체 장소만 챙기면 이 코스 그대로 좋아요!';
  }
  if (trigger.level === 'WARNING') {
    return '변수가 조금 보여요. 미리 대안만 봐 두면 안심이에요.';
  }
  return '이 코스대로 가면 좋아요. 바람 따라 안전하게 다녀오세요!';
}
