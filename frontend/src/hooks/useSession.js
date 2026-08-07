import { useState, useCallback } from 'react';

const SESSION_KEY = 'windtrail:sessionUuid';
const ITINERARY_KEY = 'windtrail:itineraryId';

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

/**
 * 세션은 유지하되, 일정 화면은 자동 복원하지 않는다.
 * (메인 대시보드로 들어왔을 때 이전 일정으로 강제 이동되는 문제 방지)
 * 저장된 일정이 있으면 draftItineraryId로만 알려 "이어하기"에 쓴다.
 */
export default function useSession() {
  const [sessionId] = useState(readSessionId);
  const [itineraryId, setItineraryIdState] = useState(null);
  const [draftItineraryId, setDraftItineraryId] = useState(readStoredItineraryId);

  const setItineraryId = useCallback((id) => {
    setItineraryIdState(id);
    if (id === null) {
      localStorage.removeItem(ITINERARY_KEY);
      setDraftItineraryId(null);
    } else {
      localStorage.setItem(ITINERARY_KEY, String(id));
      setDraftItineraryId(id);
    }
  }, []);

  /** 대시보드로 돌아갈 때 - 화면만 닫고 초안 ID는 유지(이어하기 가능) */
  const leaveItineraryView = useCallback(() => {
    setItineraryIdState(null);
    setDraftItineraryId(readStoredItineraryId());
  }, []);

  /** 저장된 초안 일정을 다시 연다 */
  const resumeDraftItinerary = useCallback(() => {
    const id = readStoredItineraryId();
    if (id != null) {
      setItineraryIdState(id);
      setDraftItineraryId(id);
    }
    return id;
  }, []);

  return {
    sessionId,
    itineraryId,
    setItineraryId,
    draftItineraryId,
    leaveItineraryView,
    resumeDraftItinerary,
  };
}
