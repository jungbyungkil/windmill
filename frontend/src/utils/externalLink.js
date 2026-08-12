/**
 * 카카오톡·인스타그램·페이스북·라인 등 인앱 브라우저는 target="_blank"를 무시하고 새 탭 대신
 * 같은 웹뷰에서 그대로 이동시키는 경우가 있다. 이 경우 외부 사이트에서 뒤로가기를 눌러도
 * 이 SPA로 복귀할 back 항목이 없어 앱이 종료된 것처럼 보인다. 인앱 브라우저에서는 이동 전
 * 현재 위치를 history에 한 번 더 쌓아두어(중복 항목), 뒤로가기 시 그 항목으로 돌아오게 한다.
 * 일반 브라우저는 기존 방식(새 탭, 히스토리 그대로 보존)을 그대로 쓴다.
 */
const IN_APP_BROWSER_UA = /KAKAOTALK|NAVER\(inapp|Instagram|FBAN|FBAV|Line\//i;

export function isInAppBrowser() {
  return IN_APP_BROWSER_UA.test(navigator.userAgent || '');
}

export function openExternalLink(url) {
  if (!url) return;
  if (isInAppBrowser()) {
    window.history.pushState({ windmillExternalGuard: true }, '', window.location.href);
    window.location.href = url;
    return;
  }
  window.open(url, '_blank', 'noopener,noreferrer');
}
