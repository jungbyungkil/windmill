import { useState } from 'react';

const DISMISS_KEY = 'windtrail:dismissedNudgeIds';

const NUDGE_STYLE = {
  WEATHER_RAIN: { icon: '🌧️', tone: 'urgent' },
  CROWD: { icon: '👥', tone: 'crowd' },
  WEATHER_HEAT: { icon: '🌡️', tone: 'heat' },
  CRUISE: { icon: '🌬️', tone: 'ok' },
};

function readDismissedIds() {
  try {
    const list = JSON.parse(localStorage.getItem(DISMISS_KEY) || '[]');
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function markDismissed(nudgeId) {
  if (!nudgeId) return;
  const next = new Set(readDismissedIds());
  next.add(nudgeId);
  try {
    localStorage.setItem(DISMISS_KEY, JSON.stringify([...next]));
  } catch {
    /* 저장 실패해도 이번 방문 동안은 부모의 dismissed state가 카드를 계속 숨겨준다 */
  }
}

/**
 * 홈 화면 넛지 카드 - 날씨/혼잡 트리거 우선순위 1건만 대표로 노출(SituationService 계산 재사용).
 * X로 닫으면 nudgeId(타입+날짜+지역)를 로컬에 저장해 같은 조건이면 재노출하지 않는다(다음 날/조건
 * 변경 시 nudgeId 자체가 바뀌어 자동으로 다시 노출됨).
 */
export default function NudgeCard({ situation, loading, onDismiss, onAction }) {
  const [dismissedIds] = useState(readDismissedIds);

  if (loading) {
    return (
      <div className="nudge-card loading" role="status">
        <span className="situation-pulse" />
        근처 여행 상황을 확인하고 있어요…
      </div>
    );
  }
  if (!situation) return null;
  if (situation.nudgeId && dismissedIds.includes(situation.nudgeId)) return null;

  const style = NUDGE_STYLE[situation.nudgeType] || NUDGE_STYLE.CRUISE;
  const message = situation.message || situation.tips?.[0];

  function handleDismiss() {
    markDismissed(situation.nudgeId);
    onDismiss?.();
  }

  return (
    <div className={`nudge-card tone-${style.tone}`} role="status">
      <button
        type="button"
        className="nudge-card-body"
        onClick={onAction ? () => onAction(situation) : undefined}
      >
        <div className="nudge-card-head">
          <span className="nudge-card-icon" aria-hidden="true">{style.icon}</span>
          <strong className="nudge-card-headline">{situation.headline}</strong>
        </div>
        <span className="nudge-card-detail">{situation.detail}</span>
        {message && <p className="nudge-card-message">{message}</p>}
      </button>
      {onDismiss && (
        <button type="button" className="icon-btn nudge-card-dismiss" onClick={handleDismiss} aria-label="닫기">
          ✕
        </button>
      )}
    </div>
  );
}

/** geolocation → API, 실패 시 null */
export async function loadSituationByGeolocation(fetchSituation) {
  if (!navigator.geolocation) return null;
  const pos = await new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: false,
      timeout: 8000,
      maximumAge: 5 * 60 * 1000,
    });
  }).catch(() => null);
  if (!pos) return null;
  return fetchSituation(pos.coords.latitude, pos.coords.longitude);
}

export function maybeNotifySituation(situation) {
  if (!situation || typeof Notification === 'undefined') return;
  const needsAlert = situation.rainAlert || situation.heatAlert || (situation.crowdedPlaceCount > 0);
  if (!needsAlert) return;
  const key = `wm-sit-${situation.regionCode}-${situation.weatherLabel}`;
  try {
    if (sessionStorage.getItem(key)) return;
    sessionStorage.setItem(key, '1');
  } catch {
    /* ignore */
  }
  const show = () => {
    try {
      new Notification(situation.headline || '바람따라 상황 알림', {
        body: situation.detail || (situation.tips?.[0] ?? ''),
        tag: 'windmill-situation',
      });
    } catch {
      /* ignore */
    }
  };
  if (Notification.permission === 'granted') {
    show();
  } else if (Notification.permission !== 'denied') {
    Notification.requestPermission().then((p) => {
      if (p === 'granted') show();
    });
  }
}
