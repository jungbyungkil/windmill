import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import * as api from '../api/windmillApi';
import { COMPANION_TYPE_OPTIONS } from '../constants';

const COMPANION_LABEL = Object.fromEntries(COMPANION_TYPE_OPTIONS.map((o) => [o.value, o.label]));

const TABS = [
  { value: undefined, label: '전체' },
  { value: 'ACTIVE', label: '진행 중' },
  { value: 'ENDED', label: '종료' },
];

const RATINGS = [
  { value: 'GOOD', icon: '👍', label: '좋았음' },
  { value: 'NEUTRAL', icon: '😐', label: '보통' },
  { value: 'BAD', icon: '👎', label: '별로' },
];

function RatingBadge({ value }) {
  const r = RATINGS.find((x) => x.value === value);
  if (!r) return <span className="trip-record-rating-badge unrated">미평가</span>;
  return (
    <span className={`trip-record-rating-badge ${value === 'BAD' ? 'bad' : ''}`}>
      {r.icon} {r.label}
    </span>
  );
}

function formatTripDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][d.getDay()];
  return `${d.getMonth() + 1}/${d.getDate()} (${weekday})`;
}

/** GNB "내 여행 관리" - 전체 일정 ACTIVE/ENDED 통합 목록, 상태별 필터 + 정리(삭제) */
export default function MyTripsScreen({ sessionId, onResume }) {
  const navigate = useNavigate();
  const [tab, setTab] = useState(undefined);
  const [trips, setTrips] = useState(null); // null = 로딩 중
  const [error, setError] = useState(null);
  const [deletingId, setDeletingId] = useState(null);

  const load = useCallback(() => {
    if (!sessionId) return;
    setTrips(null);
    setError(null);
    api.listItineraries(sessionId, { status: tab, limit: 50 })
      .then(setTrips)
      .catch((e) => {
        setError(e.message);
        setTrips([]);
      });
  }, [sessionId, tab]);

  useEffect(() => { load(); }, [load]);

  async function handleDelete(trip) {
    const label = trip.regionDisplayName ? `${trip.regionDisplayName} (${formatTripDate(trip.startDate)})` : '이 일정';
    if (!window.confirm(`${label}을(를) 삭제할까요? 되돌릴 수 없어요.`)) return;
    setDeletingId(trip.itineraryId);
    try {
      await api.deleteItinerary(trip.itineraryId);
      setTrips((prev) => (prev || []).filter((t) => t.itineraryId !== trip.itineraryId));
    } catch (e) {
      alert(`삭제 실패: ${e.message}`);
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <div className="my-trips-screen">
      <div className="my-trips-tabs">
        {TABS.map((t) => (
          <button
            key={t.label}
            type="button"
            className={`my-trips-tab ${tab === t.value ? 'active' : ''}`}
            onClick={() => setTab(t.value)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {trips === null ? (
        <div className="empty-state">불러오는 중…</div>
      ) : error ? (
        <div className="error-msg">❌ {error}</div>
      ) : trips.length === 0 ? (
        <div className="empty-state">해당하는 일정이 없어요.</div>
      ) : (
        <ul className="my-trips-list">
          {trips.map((trip) => (
            trip.status === 'ENDED' ? (
              <li key={trip.itineraryId} className="my-trips-ended-item">
                <button
                  type="button"
                  className="my-trips-ended-card"
                  onClick={() => (trip.tripRecordId
                    ? navigate(`/trip-records/${trip.tripRecordId}`)
                    : onResume(trip.itineraryId))}
                >
                  <div className="my-trips-ended-head">
                    <span className="my-trips-date">🏁 {formatTripDate(trip.startDate)}</span>
                    {trip.regionDisplayName && (
                      <span className="my-trips-region">{trip.regionDisplayName}</span>
                    )}
                    <RatingBadge value={trip.overallRating} />
                  </div>
                  <span className="my-trips-meta">
                    {trip.placeCount ?? 0}곳
                    {trip.companionType && COMPANION_LABEL[trip.companionType]
                      ? ` · ${COMPANION_LABEL[trip.companionType]}`
                      : ''}
                    {trip.withPet ? ' · 🐾' : ''}
                  </span>
                  {trip.placeNames && trip.placeNames.length > 0 && (
                    <p className="my-trips-ended-course">
                      {trip.placeNames.join(' → ')}
                    </p>
                  )}
                  {trip.overallNote ? (
                    <p className="my-trips-ended-note">"{trip.overallNote}"</p>
                  ) : (
                    <p className="my-trips-ended-note my-trips-ended-note-empty">작성한 소감이 없어요.</p>
                  )}
                </button>
                <button
                  type="button"
                  className="icon-btn danger my-trips-delete"
                  aria-label="일정 삭제"
                  onClick={() => handleDelete(trip)}
                  disabled={deletingId === trip.itineraryId}
                >
                  {deletingId === trip.itineraryId ? '…' : '🗑️'}
                </button>
              </li>
            ) : (
              <li key={trip.itineraryId} className="my-trips-row">
                <div className="my-trips-row-main">
                  <span className="my-trips-badge active">진행 중</span>
                  <span className="my-trips-date">{formatTripDate(trip.startDate)}</span>
                  {trip.regionDisplayName && (
                    <span className="my-trips-region">{trip.regionDisplayName}</span>
                  )}
                  <span className="my-trips-meta">
                    {trip.placeCount ?? 0}곳
                    {trip.companionType && COMPANION_LABEL[trip.companionType]
                      ? ` · ${COMPANION_LABEL[trip.companionType]}`
                      : ''}
                    {trip.withPet ? ' · 🐾' : ''}
                  </span>
                </div>
                <div className="my-trips-row-actions">
                  <button
                    type="button"
                    className="btn-primary my-trips-resume"
                    onClick={() => onResume(trip.itineraryId)}
                  >
                    이어하기
                  </button>
                  <button
                    type="button"
                    className="icon-btn danger my-trips-delete"
                    aria-label="일정 삭제"
                    onClick={() => handleDelete(trip)}
                    disabled={deletingId === trip.itineraryId}
                  >
                    {deletingId === trip.itineraryId ? '…' : '🗑️'}
                  </button>
                </div>
              </li>
            )
          ))}
        </ul>
      )}
    </div>
  );
}
