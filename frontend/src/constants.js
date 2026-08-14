export const TAG_OPTIONS = ['#자연', '#실내', '#맛집', '#아이동반', '#액티비티', '#역사'];

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

// 동반 자녀 만 나이 선택지 (0~17세)
export const CHILD_AGE_OPTIONS = Array.from({ length: 18 }, (_, age) => ({
  value: age,
  label: `만 ${age}세`,
}));
