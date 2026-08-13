/**
 * 실내 장소 판정 — 야외(비·폭염) 뱃지 오탐 방지.
 * 백엔드 OutdoorActivityClassifier와 같은 우선순위: #실내·#맛집·카페/식당명.
 */
export function isIndoorPlace(item) {
  if (!item) return false;
  const tags = (item.tags || []).map((t) => String(t).trim());
  if (tags.some((t) => t === '#실내' || t === '실내' || t === '#맛집' || t === '맛집')) {
    return true;
  }
  const text = `${item.category || ''} ${item.placeName || ''}`.toLowerCase();
  const indoorKeywords = [
    '박물관', '전시', '미술관', '도서관', '아트센터', '갤러리',
    '카페', '식당', '레스토랑', '맛집', '제과', '베이커리', '갈비', '불고기',
    '실내', '키즈카페', '영화관', '쇼핑몰', '백화점',
  ];
  if (indoorKeywords.some((k) => text.includes(k.toLowerCase()))) {
    return true;
  }
  const type = item.contentTypeId;
  if (type === 14 || type === 39) return true;
  return false;
}

/** 바람따라 상태 컬러 체계: NORMAL(정상) · WARNING(주의) · DANGER(긴급) */

export const STATUS_LABEL = {
  NORMAL: '정상',
  WARNING: '주의',
  DANGER: '긴급',
};

/**
 * 일정 항목 상태.
 * 야외+날씨/폭염 → DANGER, 휴무·혼잡 → WARNING
 * 실내 장소는 야외 알림으로 DANGER가 되지 않음.
 */
export function itemStatusLevel(item, {
  weatherAlerted = false,
  businessAlerted = false,
  crowdAlerted = false,
  /** @deprecated */
  alerted = false,
} = {}) {
  const weather = (weatherAlerted || alerted) && !isIndoorPlace(item);
  if (weather) return 'DANGER';
  if (businessAlerted || crowdAlerted) return 'WARNING';
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
  if (trigger.closedDayTrigger) {
    tips.push({ id: 'closedDay', icon: '🚫', text: '방문일이 정기휴무일인 장소가 있어요. 대체 일정을 골라보세요.' });
  }
  if (trigger.hoursEndedTrigger) {
    tips.push({ id: 'hoursEnded', icon: '🕐', text: '지금 영업이 끝난 장소가 있어요. 대체 일정을 골라보세요.' });
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
  if (trigger.closedDayTrigger) {
    return '쉬는 날이 끼어 있어요. 대체 장소만 챙기면 이 코스 그대로 좋아요!';
  }
  if (trigger.hoursEndedTrigger) {
    return '영업이 끝난 곳이 있어요. 대체 장소만 챙기면 이 코스 그대로 좋아요!';
  }
  if (trigger.level === 'WARNING') {
    return '변수가 조금 보여요. 미리 대안만 봐 두면 안심이에요.';
  }
  return '이 코스대로 가면 좋아요. 바람 따라 안전하게 다녀오세요!';
}
