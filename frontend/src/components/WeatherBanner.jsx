const PTY_MAP = { 0: null, 1: '비', 2: '비/눈', 3: '눈', 4: '소나기' };
const SKY_MAP = { 1: '맑음', 3: '구름많음', 4: '흐림' };

function iconFor(pty, sky) {
  if (pty === 1 || pty === 4) return '🌧️';
  if (pty === 2 || pty === 3) return '🌨️';
  if (sky === 1) return '☀️';
  if (sky === 3) return '⛅';
  if (sky === 4) return '☁️';
  return '🌤️';
}

function parseForecast(items) {
  if (!items || items.length === 0) return null;

  const byTime = new Map();
  for (const item of items) {
    const key = `${item.fcstDate}${item.fcstTime}`;
    if (!byTime.has(key)) byTime.set(key, {});
    byTime.get(key)[item.category] = item.fcstValue;
  }
  const soonestKey = [...byTime.keys()].sort()[0];
  if (!soonestKey) return null;
  const values = byTime.get(soonestKey);

  const pty = Number(values.PTY ?? 0);
  const sky = Number(values.SKY ?? 1);
  const pop = values.POP !== undefined ? Number(values.POP) : null;
  const temp = values.TMP !== undefined ? Number(values.TMP) : null;
  const humidity = values.REH !== undefined ? Number(values.REH) : null;
  const condition = PTY_MAP[pty] || SKY_MAP[sky] || '맑음';

  return { icon: iconFor(pty, sky), condition, temp, pop, humidity, rainy: pty !== 0 };
}

export default function WeatherBanner({ items }) {
  const weather = parseForecast(items);
  if (!weather) return null;

  return (
    <div className={`weather-banner ${weather.rainy ? 'rainy' : ''} ${weather.temp >= 33 ? 'heat' : ''}`}>
      <span className="weather-icon">{weather.temp >= 33 ? '🌡️' : weather.icon}</span>
      <div className="weather-info">
        <span className="weather-condition">{weather.temp >= 33 ? '폭염 주의' : weather.condition}</span>
        {weather.temp !== null && <span className="weather-temp">{weather.temp}°C</span>}
        {weather.pop !== null && <span className="weather-pop">강수확률 {weather.pop}%</span>}
        {weather.humidity !== null && <span className="weather-humidity">습도 {weather.humidity}%</span>}
      </div>
    </div>
  );
}
