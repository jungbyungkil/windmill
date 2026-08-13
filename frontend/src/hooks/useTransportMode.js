import { useState, useCallback, useEffect } from 'react';

const KEY_PREFIX = 'windtrail:transportMode:';
const VALID = ['CAR', 'WALK', 'TRANSIT'];

function readStored(itineraryId) {
  if (!itineraryId) return 'CAR';
  const v = localStorage.getItem(KEY_PREFIX + itineraryId);
  return VALID.includes(v) ? v : 'CAR';
}

/** 이동수단 선택(자차/도보/대중교통) - 이티너리 단위로 로컬 저장, 다음 접속에도 유지 */
export default function useTransportMode(itineraryId) {
  const [mode, setModeState] = useState(() => readStored(itineraryId));

  useEffect(() => {
    setModeState(readStored(itineraryId));
  }, [itineraryId]);

  const setMode = useCallback((value) => {
    if (!VALID.includes(value)) return;
    setModeState(value);
    if (itineraryId) {
      localStorage.setItem(KEY_PREFIX + itineraryId, value);
    }
  }, [itineraryId]);

  return [mode, setMode];
}
