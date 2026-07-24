const BASE_URL = import.meta.env.VITE_API_URL || '/api';

export async function createSchedule(data) {
  const res = await fetch(`${BASE_URL}/schedule`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error('일정 생성 실패');
  return res.json();
}

export async function adjustSchedule(data) {
  const res = await fetch(`${BASE_URL}/schedule/adjust`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
  if (!res.ok) throw new Error('일정 재조정 실패');
  return res.json();
}

export async function getWeather(location) {
  const res = await fetch(`${BASE_URL}/weather?location=${encodeURIComponent(location)}`);
  if (!res.ok) throw new Error('날씨 조회 실패');
  return res.json();
}

export async function getAttractions(region) {
  const res = await fetch(`${BASE_URL}/attractions?region=${encodeURIComponent(region)}`);
  if (!res.ok) throw new Error('관광지 조회 실패');
  return res.json();
}
