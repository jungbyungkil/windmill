// 바람따라 PWA 서비스 워커 - 오프라인 캐싱은 하지 않음(관광 데이터가 계속 바뀌므로 항상 최신 응답을
// 써야 함), 설치 가능성(installability)과 웹 푸시 수신을 담당한다.
// Firebase compat는 토큰 수신에 필요해서 항상 로드하고, 프로젝트 설정은 /api/public-config에서 읽는다.
importScripts(
  'https://www.gstatic.com/firebasejs/12.18.0/firebase-app-compat.js',
  'https://www.gstatic.com/firebasejs/12.18.0/firebase-messaging-compat.js',
);

const firebaseReady = fetch('/api/public-config', { cache: 'no-store' })
  .then((res) => (res.ok ? res.json() : null))
  .then((cfg) => {
    if (!cfg?.firebaseApiKey || !cfg?.firebaseProjectId || !cfg?.firebaseAppId || !cfg?.firebaseMessagingSenderId) {
      return false;
    }
    if (!self.firebase.apps.length) {
      self.firebase.initializeApp({
        apiKey: cfg.firebaseApiKey,
        authDomain: cfg.firebaseAuthDomain,
        projectId: cfg.firebaseProjectId,
        messagingSenderId: cfg.firebaseMessagingSenderId,
        appId: cfg.firebaseAppId,
      });
    }
    self.firebase.messaging();
    return true;
  })
  .catch(() => false);

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(firebaseReady);
});

self.addEventListener('activate', (event) => {
  event.waitUntil(Promise.all([self.clients.claim(), firebaseReady]));
});

self.addEventListener('fetch', () => {
  // 네트워크 그대로 통과 - 캐싱 없음
});

function payloadFromPush(event) {
  if (!event.data) return { title: '바람따라', body: '', data: {} };
  try {
    return event.data.json();
  } catch {
    return { title: '바람따라', body: event.data.text(), data: {} };
  }
}

self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    const firebaseOn = await firebaseReady;
    const payload = payloadFromPush(event);
    const notification = payload.notification || payload;
    const data = payload.data || payload;
    // FCM SDK가 notification 페이로드를 직접 띄우면 여기서 한 번 더 띄우지 않는다.
    if (firebaseOn && payload.notification) return;
    await self.registration.showNotification(notification.title || data.title || '바람따라', {
      body: notification.body || data.body || '',
      icon: '/favicon.svg',
      data: { url: data.url || '/', itineraryId: data.itineraryId || null },
    });
  })());
});

// 알림 탭 - 이미 열린 탭이 있으면 그쪽에 딥링크(url)를 postMessage로 알려 포커스만 하고,
// 없으면 새 탭을 그 URL로 연다(NotificationSchedulerService가 실어 보낸 "/?open={itineraryId}").
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = event.notification.data?.url || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) {
          client.postMessage({ type: 'windtrail:notification-click', url });
          return client.focus();
        }
      }
      return self.clients.openWindow(url);
    }),
  );
});
