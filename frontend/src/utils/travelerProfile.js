import { nowKstIso } from './kst';

export const PROFILE_KEY = 'windtrail:travelerProfile';
const SCHEMA_VERSION = 1;
const CHILD_AGE_STALE_DAYS = 180;

function isValidProfile(obj) {
  if (!obj || typeof obj !== 'object') return false;
  if (obj.v !== SCHEMA_VERSION) return false;
  if (typeof obj.savedAt !== 'string' || Number.isNaN(new Date(obj.savedAt).getTime())) return false;
  if (typeof obj.companionType !== 'string') return false;
  if (typeof obj.totalCount !== 'number' || !Number.isFinite(obj.totalCount)) return false;
  const acc = obj.accessibility;
  if (
    !acc || typeof acc !== 'object'
    || typeof acc.pet !== 'boolean'
    || typeof acc.stroller !== 'boolean'
    || typeof acc.barrierFree !== 'boolean'
  ) return false;
  if (typeof obj.adultAgeGroup !== 'string') return false;
  if (!Array.isArray(obj.childrenAges) || !obj.childrenAges.every((n) => typeof n === 'number')) return false;
  if (obj.lastRegion != null) {
    const r = obj.lastRegion;
    if (typeof r !== 'object' || typeof r.sido !== 'string' || typeof r.sigungu !== 'string') return false;
  }
  return true;
}

/**
 * 저장된 여행자 프로필을 읽는다. 스키마 버전 불일치·파싱 실패·타입 불일치·필수 필드 누락 시
 * 전부 null(저장값 없음)로 취급한다 - 부분 복원하면 이후 필드 추가 때 구버전 구조가 남아
 * 렌더링이 깨지므로(2026-09-12 브리프) 절대 하지 않는다.
 */
export function readTravelerProfile() {
  let raw;
  try {
    raw = localStorage.getItem(PROFILE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  return isValidProfile(parsed) ? parsed : null;
}

/** 개별 필드 변경 시점에 호출 - 호출 시점의 최신 값 전체를 넘겨받아 savedAt과 함께 통째로 덮어쓴다. */
export function writeTravelerProfile(fields) {
  const payload = {
    v: SCHEMA_VERSION,
    savedAt: nowKstIso(),
    companionType: fields.companionType,
    totalCount: fields.totalCount,
    accessibility: {
      pet: Boolean(fields.accessibility?.pet),
      stroller: Boolean(fields.accessibility?.stroller),
      barrierFree: Boolean(fields.accessibility?.barrierFree),
    },
    adultAgeGroup: fields.adultAgeGroup,
    childrenAges: Array.isArray(fields.childrenAges) ? fields.childrenAges : [],
  };
  if (fields.lastRegion?.sido && fields.lastRegion?.sigungu) {
    payload.lastRegion = { sido: fields.lastRegion.sido, sigungu: fields.lastRegion.sigungu };
  }
  try {
    localStorage.setItem(PROFILE_KEY, JSON.stringify(payload));
  } catch {
    // localStorage 사용 불가(프라이빗 모드 등) - 조용히 무시, 매번 새로 입력하는 것으로 폴백
  }
  return payload;
}

export function clearTravelerProfile() {
  try {
    localStorage.removeItem(PROFILE_KEY);
  } catch {
    // ignore
  }
}

/**
 * 동반 자녀 나이는 시간이 지나면 틀린 값이 된다. savedAt 기준 180일 이상 지났으면 재확인이
 * 필요하다고 본다(자녀가 없는 프로필은 해당 없음).
 */
export function isChildAgeStale(profile) {
  if (!profile || !Array.isArray(profile.childrenAges) || profile.childrenAges.length === 0) return false;
  const savedMs = new Date(profile.savedAt).getTime();
  if (Number.isNaN(savedMs)) return true;
  const elapsedDays = (Date.now() - savedMs) / (24 * 60 * 60 * 1000);
  return elapsedDays >= CHILD_AGE_STALE_DAYS;
}
