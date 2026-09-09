import { initializeApp, getApps } from 'firebase/app';
import { deleteToken, getMessaging, getToken, isSupported, onMessage } from 'firebase/messaging';
import { getPublicConfig, registerPush, unregisterPush } from '../api/windmillApi';

const PUSH_OPT_OUT_KEY = 'windtrail:pushOptOut';

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

export function isPushOptedOut() {
  try {
    return localStorage.getItem(PUSH_OPT_OUT_KEY) === '1';
  } catch {
    return false;
  }
}

export function setPushOptedOut(optedOut) {
  try {
    if (optedOut) localStorage.setItem(PUSH_OPT_OUT_KEY, '1');
    else localStorage.removeItem(PUSH_OPT_OUT_KEY);
  } catch {
    /* 저장 실패해도 이번 방문의 호출부 상태로 충분하다 */
  }
}

function envFirebaseConfig() {
  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  const vapidKey = import.meta.env.VITE_FIREBASE_VAPID_KEY;
  if (!apiKey || !vapidKey) return null;
  return {
    apiKey,
    authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
    projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
    messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
    appId: import.meta.env.VITE_FIREBASE_APP_ID,
    vapidKey,
  };
}

function configFromPublic(cfg) {
  if (!cfg?.firebaseApiKey || !cfg?.firebaseVapidKey) return null;
  return {
    apiKey: cfg.firebaseApiKey,
    authDomain: cfg.firebaseAuthDomain,
    projectId: cfg.firebaseProjectId,
    messagingSenderId: cfg.firebaseMessagingSenderId,
    appId: cfg.firebaseAppId,
    vapidKey: cfg.firebaseVapidKey,
  };
}

function isCompleteConfig(config) {
  return Boolean(
    config?.apiKey
    && config?.authDomain
    && config?.projectId
    && config?.messagingSenderId
    && config?.appId
    && config?.vapidKey,
  );
}

export async function loadFirebaseWebConfig() {
  const fromApi = configFromPublic(await getPublicConfig());
  const config = isCompleteConfig(fromApi) ? fromApi : envFirebaseConfig();
  return isCompleteConfig(config) ? config : null;
}

let foregroundHooked = false;

async function getMessagingInstance(config) {
  if (!(await isSupported())) return null;
  const app = getApps()[0] || initializeApp({
    apiKey: config.apiKey,
    authDomain: config.authDomain,
    projectId: config.projectId,
    messagingSenderId: config.messagingSenderId,
    appId: config.appId,
  });
  return getMessaging(app);
}

function hookForegroundMessages(messaging) {
  if (foregroundHooked) return;
  foregroundHooked = true;
  onMessage(messaging, (payload) => {
    const n = payload.notification || {};
    const d = payload.data || {};
    const title = n.title || d.title || '바람따라';
    const body = n.body || d.body || '';
    try {
      const notification = new Notification(title, {
        body,
        icon: '/pwa-192x192.png',
        tag: d.itineraryId ? `windmill-${d.itineraryId}` : 'windmill',
        data: { url: d.url || '/', itineraryId: d.itineraryId || null },
      });
      notification.onclick = () => {
        window.focus();
        const url = d.url || '/';
        if (url && url !== window.location.pathname + window.location.search) {
          window.location.href = url;
        }
        notification.close();
      };
    } catch {
      /* 포그라운드 배너는 실패해도 백그라운드 푸시와 무관 */
    }
  });
}

/** 이미 허용된 권한으로 FCM 토큰만 발급. 권한 팝업은 띄우지 않는다. */
export async function getExistingPushToken() {
  if (!isPushSupported()) return null;
  if (Notification.permission !== 'granted') return null;
  const config = await loadFirebaseWebConfig();
  if (!config) {
    console.info('[push] Firebase 웹 설정이 없어 토큰을 발급하지 않아요');
    return null;
  }
  const messaging = await getMessagingInstance(config);
  if (!messaging) return null;
  hookForegroundMessages(messaging);
  const registration = await navigator.serviceWorker.ready;
  try {
    return await getToken(messaging, {
      vapidKey: config.vapidKey,
      serviceWorkerRegistration: registration,
    });
  } catch (e) {
    console.warn('[push] 토큰 발급 실패', e);
    return null;
  }
}

/**
 * FCM 토큰 발급. 알림 권한을 요청한 뒤 Firebase 웹 설정이 있으면 getToken()으로 기기 토큰을 만든다.
 */
export async function requestPushToken() {
  if (!isPushSupported()) return null;
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return null;
  return getExistingPushToken();
}

/**
 * 권한이 이미 허용돼 있으면 토큰을 서버에 등록한다. 팝업은 띄우지 않는다.
 * @returns {Promise<boolean>} 서버 등록 성공 여부
 */
export async function syncPushSubscription(sessionId, itineraryId) {
  if (!sessionId || !isPushSupported()) return false;
  if (isPushOptedOut()) return false;
  if (Notification.permission !== 'granted') return false;
  const token = await getExistingPushToken();
  if (!token) return false;
  await registerPush(sessionId, { fcmToken: token, itineraryId });
  return true;
}

/** 서버 구독 삭제 + FCM 토큰 폐기. 브라우저 알림 권한 자체는 JS로 회수할 수 없다. */
export async function disablePush(sessionId) {
  let token = null;
  try {
    token = await getExistingPushToken();
  } catch {
    /* 토큰이 없어도 세션 구독은 지운다 */
  }
  await unregisterPush(sessionId, { fcmToken: token });
  try {
    const config = await loadFirebaseWebConfig();
    const messaging = config ? await getMessagingInstance(config) : null;
    if (messaging) await deleteToken(messaging);
  } catch {
    /* 토큰 폐기는 실패해도 서버에서 이미 구독이 빠지면 발송이 멈춘다 */
  }
  setPushOptedOut(true);
  return true;
}
