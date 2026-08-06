/**
 * 앱 실행 시 현재 위치(또는 선택 지역) 기반 상황 요약.
 * 비/폭염/혼잡이 있으면 브라우저 Notification으로도 한 번 알린다.
 */
export default function SituationBanner({ situation, loading, onDismiss }) {
  if (loading) {
    return (
      <div className="situation-banner loading" role="status">
        <span className="situation-pulse" />
        근처 여행 상황을 확인하고 있어요…
      </div>
    );
  }
  if (!situation) return null;

  const alert = situation.rainAlert || situation.heatAlert || (situation.crowdedPlaceCount > 0);
  return (
    <div className={`situation-banner ${alert ? 'alert' : 'ok'}`} role="status">
      <div className="situation-body">
        <strong className="situation-headline">{situation.headline}</strong>
        <span className="situation-detail">{situation.detail}</span>
        {situation.tips?.length > 0 && (
          <ul className="situation-tips">
            {situation.tips.slice(0, 2).map((tip) => (
              <li key={tip}>{tip}</li>
            ))}
          </ul>
        )}
      </div>
      {onDismiss && (
        <button type="button" className="icon-btn situation-dismiss" onClick={onDismiss} aria-label="닫기">
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
