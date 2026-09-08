// 2026-08-16 사용자 요청으로 세분화 - 맛집(한식/중식/일식/양식/카페), 실내(박물관/미술관/전시),
// 역사(고궁), 액티비티(방탈출) 등 하위 태그를 추가. 기존 상위 태그(#맛집/#실내/#역사/#액티비티)는
// "특별히 안 가리고 싶을 때" 쓰는 캐치올로 그대로 남겨둠.
// 이어서 한국관광공사 categoryCode2 실제 데이터를 참고해 12종을 추가로 확장(전통시장/온천스파/
// 테마파크/사찰/전통체험/캠핑/등산트레킹/수상레포츠/쇼핑/공방체험/공연장/이색거리).
// 2026-08-18 사용자 요청으로 28개 태그를 5개 카테고리로 그룹핑(검색 시 한눈에 훑어보기 쉽도록).
export const TAG_GROUPS = [
  { label: '맛집·카페', tags: ['#맛집', '#한식', '#중식', '#일식', '#양식', '#카페'] },
  { label: '실내 즐길거리', tags: ['#실내', '#박물관', '#미술관', '#전시', '#방탈출', '#공연장'] },
  { label: '역사·전통', tags: ['#역사', '#고궁', '#사찰', '#전통시장', '#전통체험'] },
  { label: '자연·액티비티', tags: ['#자연', '#액티비티', '#캠핑', '#등산트레킹', '#수상레포츠', '#온천스파', '#테마파크'] },
  { label: '쇼핑·체험', tags: ['#아이동반', '#쇼핑', '#공방체험', '#이색거리'] },
];

export const TAG_OPTIONS = TAG_GROUPS.flatMap((group) => group.tags);

// 첫 화면 동반유형 단일선택 옵션 - 반려동물 동반 여부는 별도 체크박스(withPet)
export const COMPANION_TYPE_OPTIONS = [
  { value: 'SOLO', label: '1인' },
  { value: 'COUPLE', label: '2인' },
  { value: 'TRIO', label: '3인' },
  { value: 'FAMILY_4', label: '4인 가족' },
  { value: 'EXTENDED_FAMILY', label: '대가족' },
];

// 동반유형별 고정 총 인원수 - EXTENDED_FAMILY(대가족)는 없음(사용자 직접 입력, 5~9명)
export const FIXED_PARTY_SIZE_BY_COMPANION_TYPE = {
  SOLO: 1,
  COUPLE: 2,
  TRIO: 3,
  FAMILY_4: 4,
};

export const EXTENDED_FAMILY_MIN_SIZE = 5;
export const EXTENDED_FAMILY_MAX_SIZE = 9;

// 첫 화면 성인 연령대 단일선택(필수) - 동반 자녀는 개별 만 나이로 별도 입력(CHILD_AGE_OPTIONS)
// 한 줄에 들어오도록 50대 이상은 FIFTIES 하나로 통합(백엔드 AgeGroup enum의 SIXTIES/SEVENTIES_PLUS는
// 기존 저장값 호환을 위해 유지, 신규 선택만 FIFTIES로 수렴).
export const AGE_GROUP_OPTIONS = [
  { value: 'TWENTIES', label: '20대' },
  { value: 'THIRTIES', label: '30대' },
  { value: 'FORTIES', label: '40대' },
  { value: 'FIFTIES', label: '50대 이상' },
];

// 식당·카페 고를 때 1인 식사 참고 필터(이하). 일정 합계용이 아님. 값 없음(null)이면 필터 없음
export const BUDGET_OPTIONS = [
  { value: 10000, label: '1만원 이하' },
  { value: 20000, label: '2만원 이하' },
  { value: 30000, label: '3만원 이하' },
  { value: 40000, label: '4만원 이하' },
  { value: 50000, label: '5만원 이하' },
];

export const FOOD_TAGS = TAG_GROUPS[0].tags;
/** TourAPI 음식점 contentTypeId */
export const TOUR_FOOD_CONTENT_TYPE_ID = 39;

const FOOD_QUERY_HINTS = ['맛집', '식당', '음식', '카페', '한식', '중식', '일식', '양식', '레스토랑'];

export function isFoodPlace(place) {
  if (!place) return false;
  if (Number(place.contentTypeId) === TOUR_FOOD_CONTENT_TYPE_ID) return true;
  const tags = place.matchedTags || place.tags || [];
  return tags.some((t) => FOOD_TAGS.includes(t));
}

export function isFoodSearch({ tags = [], query = '' } = {}) {
  if (tags.some((t) => FOOD_TAGS.includes(t))) return true;
  const q = (query || '').trim();
  return q.length > 0 && FOOD_QUERY_HINTS.some((hint) => q.includes(hint));
}

// 동반 자녀 만 나이 선택지 (0~17세)
export const CHILD_AGE_OPTIONS = Array.from({ length: 18 }, (_, age) => ({
  value: age,
  label: `만 ${age}세`,
}));
