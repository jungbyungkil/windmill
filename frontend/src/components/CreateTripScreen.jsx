import { useState, useEffect } from 'react';
import PinwheelHero from './PinwheelHero';
import WeatherBanner from './WeatherBanner';
import * as api from '../api/windmillApi';
import { SOKCHO_NX, SOKCHO_NY } from '../constants';

export default function CreateTripScreen({ onCreate, loading, error }) {
  // 위치기반 상황 알림: 앱 실행 즉시(일정 생성 전) 현재 속초 날씨를 보여준다.
  // 실제 OS 푸시 알림은 서비스워커/VAPID 인프라가 필요해 MVP 범위 밖 — 인앱 배너로 대체.
  const [weatherItems, setWeatherItems] = useState(null);

  useEffect(() => {
    api.getWeather(SOKCHO_NX, SOKCHO_NY).then(setWeatherItems).catch(() => setWeatherItems(null));
  }, []);

  return (
    <div className="create-trip-screen">
      <PinwheelHero />
      <h1 className="brand-title">바람따라</h1>
      <p className="brand-tagline">바람이 알려주는 실시간 여행, 속초를 바람따라 걸어보세요</p>

      <WeatherBanner items={weatherItems} />

      <button className="btn-primary btn-start" onClick={onCreate} disabled={loading}>
        {loading ? '여행 준비 중...' : '🌊 속초 여행 시작하기'}
      </button>

      {error && <div className="error-msg">❌ {error}</div>}

      <p className="create-trip-hint">
        비가 오거나, 붐비거나, 동선이 꼬이면 바람개비가 알아서 신호를 보내요.
      </p>
    </div>
  );
}
