// 바람따라 PWA 서비스 워커 - 오프라인 캐싱은 하지 않음(관광 데이터가 계속 바뀌므로 항상 최신 응답을
// 써야 함), 설치 가능성(installability)과 향후 웹 푸시 수신을 위한 최소 등록만 담당한다.
self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', () => {
  // 네트워크 그대로 통과 - 캐싱 없음
});

// Firebase 프로젝트 연결 후: firebase-messaging-compat.js importScripts + onBackgroundMessage로
// 백그라운드 알림 표시 로직을 여기 추가하면 된다.
self.addEventListener('push', (event) => {
  if (!event.data) return;
  let payload;
  try {
    payload = event.data.json();
  } catch {
    payload = { title: '바람따라', body: event.data.text() };
  }
  // FCM 웹푸시는 {notification:{...}, data:{...}} 형태로 실려온다 - notification/data 블록과
  // 최상위 필드(직접 테스트용 payload) 둘 다 방어적으로 처리한다.
  const notification = payload.notification || payload;
  const data = payload.data || payload;
  event.waitUntil(
    self.registration.showNotification(notification.title || '바람따라', {
      body: notification.body || '',
      icon: '/favicon.svg',
      data: { url: data.url || '/', itineraryId: data.itineraryId || null },
    }),
  );
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
