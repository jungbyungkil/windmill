import { useState, useCallback } from 'react';

const SESSION_KEY = 'windtrail:sessionUuid';
const ITINERARY_KEY = 'windtrail:itineraryId';
const DRAFT_IDS_KEY = 'windtrail:draftItineraryIds';

function readSessionId() {
  let id = localStorage.getItem(SESSION_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(SESSION_KEY, id);
  }
  return id;
}

function readStoredItineraryId() {
  const stored = localStorage.getItem(ITINERARY_KEY);
  if (!stored) return null;
  const n = Number(stored);
  return Number.isFinite(n) ? n : null;
}

function readDraftIds() {
  try {
    const raw = localStorage.getItem(DRAFT_IDS_KEY);
    const list = raw ? JSON.parse(raw) : [];
    const fromList = Array.isArray(list)
      ? list.map(Number).filter((n) => Number.isFinite(n))
      : [];
    const last = readStoredItineraryId();
    if (last != null) fromList.push(last);
    return [...new Set(fromList)];
  } catch {
    const last = readStoredItineraryId();
    return last != null ? [last] : [];
  }
}

function writeDraftIds(ids) {
  const unique = [...new Set(ids.map(Number).filter((n) => Number.isFinite(n)))];
  localStorage.setItem(DRAFT_IDS_KEY, JSON.stringify(unique));
  return unique;
}

function addDraftId(id) {
  const n = Number(id);
  if (!Number.isFinite(n)) return readDraftIds();
  return writeDraftIds([...readDraftIds(), n]);
}

function removeDraftId(id) {
  const n = Number(id);
  return writeDraftIds(readDraftIds().filter((x) => x !== n));
}

/**
 * 세션은 유지하되, 일정 화면은 자동 복원하지 않는다.
 * 여러 당일치기 초안 ID를 로컬에 모아 두고, 서버 ongoing 목록과 함께 쓴다.
 */
export default function useSession() {
  const [sessionId] = useState(readSessionId);
  const [itineraryId, setItineraryIdState] = useState(null);
  const [draftItineraryId, setDraftItineraryId] = useState(readStoredItineraryId);
  const [draftItineraryIds, setDraftItineraryIds] = useState(readDraftIds);

  const setItineraryId = useCallback((id) => {
    setItineraryIdState(id);
    if (id === null) {
      const prev = readStoredItineraryId();
      localStorage.removeItem(ITINERARY_KEY);
      setDraftItineraryId(null);
      if (prev != null) {
        setDraftItineraryIds(removeDraftId(prev));
      }
    } else {
      localStorage.setItem(ITINERARY_KEY, String(id));
      setDraftItineraryId(id);
      setDraftItineraryIds(addDraftId(id));
    }
  }, []);

  /** 대시보드로 돌아갈 때 - 화면만 닫고 초안 ID들은 유지 */
  const leaveItineraryView = useCallback(() => {
    setItineraryIdState(null);
    const last = readStoredItineraryId();
    setDraftItineraryId(last);
    setDraftItineraryIds(readDraftIds());
  }, []);

  /** 특정(또는 최근) 초안 일정을 다시 연다 */
  const resumeDraftItinerary = useCallback((id) => {
    const target = id != null ? Number(id) : readStoredItineraryId();
    if (target == null || !Number.isFinite(target)) return null;
    localStorage.setItem(ITINERARY_KEY, String(target));
    setItineraryIdState(target);
    setDraftItineraryId(target);
    setDraftItineraryIds(addDraftId(target));
    return target;
  }, []);

  return {
    sessionId,
    itineraryId,
    setItineraryId,
    draftItineraryId,
    draftItineraryIds,
    leaveItineraryView,
    resumeDraftItinerary,
  };
}
