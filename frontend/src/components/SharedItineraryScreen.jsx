import { useEffect, useState } from 'react';
import * as api from '../api/windmillApi';

export default function SharedItineraryScreen({ token, onBack }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.getSharedItinerary(token)
      .then((res) => {
        if (!cancelled) setData(res);
      })
      .catch((e) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [token]);

  if (loading) {
    return (
      <div className="share-screen">
        <p className="modal-desc">공유 일정을 불러오는 중…</p>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="share-screen">
        <h1 className="brand-title">바람따라</h1>
        <p className="error-msg">공유 일정을 찾을 수 없어요.</p>
        {onBack && <button type="button" className="btn-secondary" onClick={onBack}>홈으로</button>}
      </div>
    );
  }

  const byDate = {};
  (data.items || []).forEach((item) => {
    const d = item.visitDate || data.startDate || '일정';
    if (!byDate[d]) byDate[d] = [];
    byDate[d].push(item);
  });

  return (
    <div className="share-screen">
      <header className="share-header">
        <span className="logo">🌬️ 바람따라</span>
        <span className="share-badge">공유된 일정</span>
      </header>
      <h1 className="share-title">{data.regionDisplayName}</h1>
      <p className="share-meta">
        {data.startDate}
        {data.endDate && data.endDate !== data.startDate ? ` ~ ${data.endDate}` : ''}
        {data.companionType ? ` · ${data.companionType}` : ''}
        {data.withPet ? ' · 반려동물' : ''}
      </p>

      {Object.entries(byDate).map(([date, items]) => (
        <section key={date} className="share-day">
          <h2 className="share-day-title">{date}</h2>
          <ol className="share-item-list">
            {items.map((item, idx) => (
              <li key={`${item.placeName}-${idx}`} className="share-item">
                {item.thumbnailUrl && (
                  <img src={item.thumbnailUrl} alt="" className="share-thumb" />
                )}
                <div>
                  <div className="share-place">{item.scheduledTime ? `${item.scheduledTime} · ` : ''}{item.placeName}</div>
                  {item.category && <div className="share-cat">{item.category}</div>}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ))}

      {onBack && (
        <button type="button" className="btn-primary" onClick={onBack}>
          내 여행 만들기
        </button>
      )}
    </div>
  );
}
