export default function WeatherBanner({ weather }) {
  if (!weather) return null;

  return (
    <div className={`weather-banner ${weather.condition === '비' ? 'rainy' : ''}`}>
      <span className="weather-icon">{weather.icon}</span>
      <div className="weather-info">
        <span className="weather-location">{weather.location}</span>
        <span className="weather-condition">{weather.condition}</span>
        <span className="weather-temp">{weather.temperature}°C</span>
        <span className="weather-humidity">습도 {weather.humidity}%</span>
      </div>
      {weather.warning && (
        <div className="weather-warning">⚠️ {weather.warning}</div>
      )}
    </div>
  );
}
