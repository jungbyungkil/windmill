import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import * as api from '../api/windmillApi';

function formatFullDate(dateStr, dayOfWeek) {
  if (!dateStr) return '';
  const [y, m, d] = dateStr.split('-');
  return `${y}.${m}.${d}${dayOfWeek ? ` (${dayOfWeek})` : ''}`;
}

/** GNB "여행 기록" - 완료한 당일치기를 날짜순으로 훑어보는 화면. 카드 탭 시 그 여행의 일기 상세로 이동한다. */
export default function TripRecordsScreen({ sessionId, onStartNew }) {
  const navigate = useNavigate();
  const [records, setRecords] = useState(null); // null = 로딩 중
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    if (!sessionId) return;
    setRecords(null);
    setError(null);
    api.listTripRecords(sessionId, { limit: 30 })
      .then(setRecords)
      .catch((e) => {
        setError(e.message);
        setRecords([]);
      });
  }, [sessionId]);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="trip-records-screen">
      {records === null ? (
        <div className="empty-state">불러오는 중…</div>
      ) : error ? (
        <div className="error-msg">❌ {error}</div>
      ) : records.length === 0 ? (
        <div className="trip-records-empty">
          <p className="empty-state">아직 완료한 여행 기록이 없어요.</p>
          {onStartNew && (
            <button type="button" className="btn-primary" onClick={onStartNew}>
              🌬️ 당일치기 시작하기
            </button>
          )}
        </div>
      ) : (
        <ul className="trip-records-list">
          {records.map((r) => (
            <li key={r.tripRecordId}>
              <button
                type="button"
                className="trip-record-card"
                onClick={() => navigate(`/trip-records/${r.tripRecordId}`)}
              >
                <span className="trip-record-date">
                  📅 {formatFullDate(r.scheduledDate, r.dayOfWeek)} — {r.regionDisplayName || '지역 미상'}
                </span>
                {r.summaryText ? (
                  <p className="trip-record-note">"{r.summaryText}"</p>
                ) : (
                  <p className="trip-record-note trip-record-note-empty">작성한 소감이 없어요.</p>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
