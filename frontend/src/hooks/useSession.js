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

export default function useSession() {
  const [sessionId] = useState(readSessionId);
  const [itineraryId, setItineraryIdState] = useState(() => {
    const stored = localStorage.getItem(ITINERARY_KEY);
    return stored ? Number(stored) : null;
  });

  const setItineraryId = useCallback((id) => {
    setItineraryIdState(id);
    if (id === null) {
      localStorage.removeItem(ITINERARY_KEY);
    } else {
      localStorage.setItem(ITINERARY_KEY, String(id));
    }
  }, []);

  return { sessionId, itineraryId, setItineraryId };
}
