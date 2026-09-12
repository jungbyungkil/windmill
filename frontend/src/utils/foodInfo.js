import { sanitizeApiText } from './sanitizeApiText';

// TourAPI 표준분류 소분류 코드(cat3) - 음식점(A0502) 하위 분류
const CUISINE_LABEL_BY_CAT3 = {
  A05020100: '한식',
  A05020200: '양식',
  A05020300: '일식',
  A05020400: '중식',
  A05020500: '아시아식',
  A05020600: '패밀리레스토랑',
  A05020700: '이색음식점',
  A05020800: '채식전문점',
  A05020900: '카페',
  A05021000: '클럽',
};

/** cat3 코드로 음식 종류 라벨을 찾는다. 매핑에 없으면(음식점이 아니거나 신규 코드) null. */
export function cuisineLabel(cat3) {
  if (!cat3) return null;
  return CUISINE_LABEL_BY_CAT3[cat3.toUpperCase()] || null;
}

/** detailFacts에서 대표메뉴(firstmenu)를 우선하고 없으면 취급메뉴(treatmenu)를 쓴다. */
export function mainMenuFrom(detailFacts) {
  if (!Array.isArray(detailFacts)) return null;
  const byKey = (key) => detailFacts.find((f) => f?.key === key && String(f.value || '').trim());
  const fact = byKey('firstmenu') || byKey('treatmenu');
  return fact ? sanitizeApiText(fact.value).trim() : null;
}

/** 카드 이름 아래에 보여줄 "한식 · 대표메뉴" 형태의 한 줄. 정보가 하나도 없으면 빈 문자열. */
export function foodSummaryLine(item) {
  if (item?.contentTypeId !== 39) return '';
  const cuisine = cuisineLabel(item.cat3);
  const menu = mainMenuFrom(item.detailFacts);
  return [cuisine, menu].filter(Boolean).join(' · ');
}
