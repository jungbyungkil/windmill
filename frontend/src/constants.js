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
  { value: 'SOLO', label: '1인 여행' },
  { value: 'COUPLE', label: '2인 여행' },
  { value: 'FAMILY_4', label: '4인 가족 여행' },
  { value: 'EXTENDED_FAMILY', label: '대가족 여행' },
];

// 첫 화면 성인 연령대 단일선택(필수) - 동반 자녀는 개별 만 나이로 별도 입력(CHILD_AGE_OPTIONS)
export const AGE_GROUP_OPTIONS = [
  { value: 'TWENTIES', label: '20대' },
  { value: 'THIRTIES', label: '30대' },
  { value: 'FORTIES', label: '40대' },
  { value: 'FIFTIES', label: '50대' },
  { value: 'SIXTIES', label: '60대' },
  { value: 'SEVENTIES_PLUS', label: '70대 이상' },
];

// 검색 시 예산 필터(1인 기준, 이하) - 단일 선택. 값 없음(null)이면 필터 없음(전체)
export const BUDGET_OPTIONS = [
  { value: 10000, label: '1만원 이하' },
  { value: 20000, label: '2만원 이하' },
  { value: 30000, label: '3만원 이하' },
  { value: 40000, label: '4만원 이하' },
  { value: 50000, label: '5만원 이하' },
];

// 동반 자녀 만 나이 선택지 (0~17세)
export const CHILD_AGE_OPTIONS = Array.from({ length: 18 }, (_, age) => ({
  value: age,
  label: `만 ${age}세`,
}));
