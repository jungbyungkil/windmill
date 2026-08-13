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
  event.waitUntil(
    self.registration.showNotification(payload.title || '바람따라', {
      body: payload.body || '',
      icon: '/favicon.svg',
    }),
  );
});
