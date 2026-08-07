function iconFor(wf, rainRisk, heatRisk) {
  if (heatRisk) return '🌡️';
  if (rainRisk || (wf && (wf.includes('비') || wf.includes('소나기')))) return '🌧️';
  if (wf && wf.includes('눈')) return '🌨️';
  if (wf && wf.includes('흐림')) return '☁️';
  if (wf && wf.includes('구름')) return '⛅';
  if (wf && wf.includes('맑')) return '☀️';
  return '🌤️';
}

function formatMd(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/**
 * 중기예보(3~10일) 가로 스크롤 참고 배너.
 * 단기보다 거친 전망이라 "참고"로 표시한다.
 */
export default function MidWeatherBanner({ forecast }) {
  if (!forecast?.days?.length) return null;

  return (
    <section className="mid-weather-banner" aria-label="중기예보">
      <div className="mid-weather-head">
        <span className="mid-weather-badge">중기 · 참고</span>
        <div>
          <strong>{forecast.summary || '3~10일 날씨 전망'}</strong>
          <p>단기(오늘~모레)보다 거친 예보예요. 여행 며칠 앞을 미리 가늠할 때 참고하세요.</p>
        </div>
      </div>
      <div className="mid-weather-list" role="list">
        {forecast.days.map((day) => {
          const wf = day.pmWeather || day.amWeather || '-';
          const pop = day.pmRainPercent ?? day.amRainPercent;
          return (
            <article
              key={day.date}
              className={`mid-weather-card ${day.rainRisk ? 'rain' : ''} ${day.heatRisk ? 'heat' : ''}`}
              role="listitem"
            >
              <div className="mid-weather-date">
                <span className="mid-weather-wd">{day.weekday}</span>
                <span>{formatMd(day.date)}</span>
              </div>
              <div className="mid-weather-icon">{iconFor(wf, day.rainRisk, day.heatRisk)}</div>
              <div className="mid-weather-wf">{wf}</div>
              <div className="mid-weather-meta">
                {(day.minTemp != null || day.maxTemp != null) && (
                  <span>
                    {day.minTemp != null ? `${day.minTemp}°` : '-'}
                    /
                    {day.maxTemp != null ? `${day.maxTemp}°` : '-'}
                  </span>
                )}
                {pop != null && <span>강수 {pop}%</span>}
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}
