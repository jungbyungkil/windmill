/**
 * 카카오맵 URL Scheme / 웹 폴백.
 * KorService2: mapX=경도(lon), mapY=위도(lat)
 * 카카오 Scheme p= 파라미터는 위도,경도(lat,lon) 순서.
 */

const MOBILE_UA = /iPhone|iPad|iPod|Android/i;

function parseCoords(mapX, mapY) {
  const lon = Number(mapX);
  const lat = Number(mapY);
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) return null;
  if (lon === 0 && lat === 0) return null;
  return { lat, lon };
}

export function isMobileKakaoClient() {
  if (typeof navigator === 'undefined') return false;
  return MOBILE_UA.test(navigator.userAgent || '');
}

export function canOpenInKakaoMap(place) {
  if (!place) return false;
  if (parseCoords(place.mapX, place.mapY)) return true;
  return Boolean(place.placeName || place.name || place.addr1);
}

/**
 * 동선 아이템을 카카오맵에서 연다.
 * 모바일+좌표: 앱 딥링크 → 미설치 시 모바일웹 스킴 폴백
 * PC/좌표없음: map.kakao.com 웹 링크
 */
export function openInKakaoMap(place) {
  if (!place || typeof window === 'undefined') return;

  const name = (place.placeName || place.name || '').trim();
  const addr = (place.addr1 || '').trim();
  const coords = parseCoords(place.mapX, place.mapY);
  const mobile = isMobileKakaoClient();

  if (mobile && coords) {
    const appUrl = `kakaomap://look?p=${coords.lat},${coords.lon}`;
    const mobileWebUrl = `http://m.map.kakao.com/scheme/look?p=${coords.lat},${coords.lon}`;
    tryOpenAppThenFallback(appUrl, mobileWebUrl);
    return;
  }

  if (coords) {
    const label = encodeURIComponent(name || addr || '장소');
    window.open(
      `https://map.kakao.com/link/map/${label},${coords.lat},${coords.lon}`,
      '_blank',
      'noopener,noreferrer',
    );
    return;
  }

  const query = name || addr;
  if (!query) return;
  window.open(
    `https://map.kakao.com/link/search/${encodeURIComponent(query)}`,
    '_blank',
    'noopener,noreferrer',
  );
}

/**
 * 키워드 검색(좌표 주변). 동명이인 보완용.
 */
export function openInKakaoMapSearch(place) {
  if (!place || typeof window === 'undefined') return;
  const name = (place.placeName || place.name || place.addr1 || '').trim();
  if (!name) return;
  const coords = parseCoords(place.mapX, place.mapY);
  const mobile = isMobileKakaoClient();

  if (mobile && coords) {
    const q = encodeURIComponent(name);
    const appUrl = `kakaomap://search?q=${q}&p=${coords.lat},${coords.lon}`;
    const mobileWebUrl = `http://m.map.kakao.com/scheme/search?q=${q}&p=${coords.lat},${coords.lon}`;
    tryOpenAppThenFallback(appUrl, mobileWebUrl);
    return;
  }

  window.open(
    `https://map.kakao.com/link/search/${encodeURIComponent(name)}`,
    '_blank',
    'noopener,noreferrer',
  );
}

/**
 * 두 지점 자동차 길찾기 (나중에 "다음 장소까지" 버튼용).
 * @param {'car'|'foot'|'bicycle'|'publictransit'} by
 */
export function openKakaoMapRoute(from, to, by = 'car') {
  if (!from || !to || typeof window === 'undefined') return;
  const a = parseCoords(from.mapX, from.mapY);
  const b = parseCoords(to.mapX, to.mapY);
  if (!a || !b) {
    openInKakaoMap(to);
    return;
  }
  const mobile = isMobileKakaoClient();
  if (mobile) {
    const appUrl = `kakaomap://route?sp=${a.lat},${a.lon}&ep=${b.lat},${b.lon}&by=${by}`;
    const mobileWebUrl = `http://m.map.kakao.com/scheme/route?sp=${a.lat},${a.lon}&ep=${b.lat},${b.lon}&by=${by}`;
    tryOpenAppThenFallback(appUrl, mobileWebUrl);
    return;
  }
  window.open(
    `https://map.kakao.com/link/to/${encodeURIComponent(to.placeName || to.name || '도착')},${b.lat},${b.lon}`,
    '_blank',
    'noopener,noreferrer',
  );
}

function tryOpenAppThenFallback(appUrl, fallbackUrl) {
  let leftPage = false;
  const markLeft = () => {
    leftPage = true;
  };
  document.addEventListener('visibilitychange', markLeft);
  window.addEventListener('pagehide', markLeft);
  window.addEventListener('blur', markLeft);

  window.location.href = appUrl;

  window.setTimeout(() => {
    document.removeEventListener('visibilitychange', markLeft);
    window.removeEventListener('pagehide', markLeft);
    window.removeEventListener('blur', markLeft);
    if (!leftPage && !document.hidden) {
      window.location.href = fallbackUrl;
    }
  }, 1200);
}
