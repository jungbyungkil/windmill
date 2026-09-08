import { useEffect, useState } from 'react';

/**
 * contentTypeId → 대체 이모지. 스톡 아이콘 대신 이모지를 쓰면 라이선스 부담이 없고
 * 기존 🌬️ 플레이스홀더와 결이 같다. SVG 세트로 바꾸려면 이 맵과 place-thumb-fallback만 손대면 된다.
 * 12 관광지 · 14 문화시설 · 15 축제공연행사 · 25 여행코스 · 28 레포츠 · 32 숙박 · 38 쇼핑 · 39 음식점
 */
const TYPE_EMOJI = {
  12: '🏛️',
  14: '🎨',
  15: '🎪',
  25: '🗺️',
  28: '🏄',
  32: '🛏️',
  38: '🛍️',
  39: '🍽️',
};

const CAFE_RE = /카페|커피|coffee|베이커리|디저트|dessert|브런치|로스터/i;
const RESTAURANT_RE = /맛집|식당|한식|분식|국밥|해장|횟집|고기|초밥|국수|칼국수|면옥|정식|뷔페/;
const NATURE_RE = /해수욕장|해변|해안|바다|계곡|산림|수목원|공원|폭포|섬|호수|둘레길|트레킹/;

/** 이미지가 없을 때 카드에 넣을 카테고리 이모지 */
export function placeFallbackEmoji(item) {
  if (!item) return '🌬️';
  const text = `${item.category || ''} ${item.placeName || ''}`;
  if (CAFE_RE.test(text)) return '☕';
  const byType = TYPE_EMOJI[item.contentTypeId];
  if (byType) return byType;
  if (RESTAURANT_RE.test(text)) return '🍽️';
  if (NATURE_RE.test(text)) return '🏞️';
  return '🌬️';
}

/**
 * 장소 썸네일 - firstimage(thumbnailUrl) 우선, URL이 없거나 로딩에 실패하면
 * contentTypeId 기준 카테고리 이모지 타일로 대체한다. 목록 좌측 고정 소형 슬롯용.
 */
export default function PlaceThumb({ item, className = '' }) {
  const [failed, setFailed] = useState(false);
  const url = item?.thumbnailUrl;

  useEffect(() => {
    setFailed(false);
  }, [url]);

  if (!url || failed) {
    return (
      <span className={`place-thumb place-thumb-fallback ${className}`} aria-hidden="true">
        {placeFallbackEmoji(item)}
      </span>
    );
  }

  return (
    <img
      className={`place-thumb ${className}`}
      src={url}
      alt=""
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
    />
  );
}
