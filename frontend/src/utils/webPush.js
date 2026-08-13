export function isIOS() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
}

export function isStandalone() {
  return Boolean(
    window.matchMedia?.('(display-mode: standalone)').matches
    || window.navigator.standalone === true,
  );
}

export function isPushSupported() {
  return 'serviceWorker' in navigator && 'PushManager' in window && typeof Notification !== 'undefined';
}

/**
 * FCM 토큰 발급. Firebase 프로젝트(VITE_FIREBASE_* 환경변수)가 아직 연결되지 않아 항상 null을
 * 반환한다 - 알림 권한만 먼저 확보해두고, 실제 토큰 발급은 Firebase 연결 후
 * firebase/messaging의 getToken(messaging, { vapidKey, serviceWorkerRegistration })으로 교체.
 */
export async function requestPushToken() {
  if (!isPushSupported()) return null;
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return null;
  if (!import.meta.env.VITE_FIREBASE_API_KEY) {
    console.info('[push] Firebase 프로젝트가 아직 연결되지 않았어요 (VITE_FIREBASE_* 환경변수 필요)');
    return null;
  }
  return null;
}
