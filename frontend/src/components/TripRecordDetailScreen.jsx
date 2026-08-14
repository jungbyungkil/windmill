import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import * as api from '../api/windmillApi';

const RATINGS = [
  { value: 'GOOD', icon: '👍', label: '좋았음' },
  { value: 'NEUTRAL', icon: '😐', label: '보통' },
  { value: 'BAD', icon: '👎', label: '별로' },
];

function formatFullDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][d.getDay()];
  const [y, m, day] = dateStr.split('-');
  return `${y}.${m}.${day} (${weekday})`;
}

function RatingBadge({ value }) {
  const r = RATINGS.find((x) => x.value === value);
  if (!r) return <span className="trip-record-rating-badge unrated">미평가</span>;
  return (
    <span className={`trip-record-rating-badge ${value === 'BAD' ? 'bad' : ''}`}>
      {r.icon} {r.label}
    </span>
  );
}

/** 여행 기록(일기) 상세 - 완료 시 태깅한 내용을 다시 읽어보고, 필요하면 한줄 의견/장소별 메모를 고친다. */
export default function TripRecordDetailScreen({ sessionId, onViewItinerary }) {
  const { tripRecordId } = useParams();
  const [record, setRecord] = useState(null);
  const [itineraryItems, setItineraryItems] = useState([]);
  const [itineraryMeta, setItineraryMeta] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [editing, setEditing] = useState(false);
  const [overallRating, setOverallRating] = useState(null);
  const [overallNote, setOverallNote] = useState('');
  const [ratings, setRatings] = useState({});
  const [memos, setMemos] = useState({});
  const [saving, setSaving] = useState(false);

  const resetFormFromRecord = useCallback((rec, items) => {
    setOverallRating(rec.overallRating);
    setOverallNote(rec.overallNote || '');
    const source = items.length > 0 ? items : (rec.visitFeedback || []);
    const nextRatings = {};
    const nextMemos = {};
    const byItemId = Object.fromEntries((rec.visitFeedback || []).map((f) => [f.itemId, f]));
    for (const entry of source) {
      const fb = byItemId[entry.itemId];
      if (fb) {
        nextRatings[entry.itemId] = fb.rating;
        nextMemos[entry.itemId] = fb.memo || '';
      }
    }
    setRatings(nextRatings);
    setMemos(nextMemos);
  }, []);

  useEffect(() => {
    if (!sessionId || !tripRecordId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.getTripRecordDetail(tripRecordId, sessionId)
      .then(async (rec) => {
        if (cancelled) return;
        setRecord(rec);
        resetFormFromRecord(rec, []);
        if (rec.itineraryId) {
          try {
            const itin = await api.getItinerary(rec.itineraryId);
            if (cancelled) return;
            setItineraryItems(itin.items || []);
            setItineraryMeta({ regionDisplayName: itin.regionDisplayName, startDate: itin.startDate });
            resetFormFromRecord(rec, itin.items || []);
          } catch {
            // 일정이 그 사이 삭제됐을 수 있음 - 일기 텍스트 자체는 그대로 보여준다
          }
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [sessionId, tripRecordId, resetFormFromRecord]);

  function setRating(itemId, rating) {
    setRatings((prev) => ({ ...prev, [itemId]: rating }));
  }

  function setMemo(itemId, memo) {
    setMemos((prev) => ({ ...prev, [itemId]: memo }));
  }

  function handleStartEdit() {
    setEditing(true);
  }

  function handleCancelEdit() {
    resetFormFromRecord(record, editableItems);
    setEditing(false);
  }

  async function handleSave() {
    setSaving(true);
    try {
      const visitFeedback = editableItems
        .filter((item) => ratings[item.itemId])
        .map((item) => ({
          itemId: item.itemId,
          placeName: item.placeName,
          rating: ratings[item.itemId],
          memo: (memos[item.itemId] || '').trim() || null,
        }));
      const updated = await api.updateTripRecord(tripRecordId, sessionId, {
        overallRating,
        overallNote: overallNote.trim(),
        visitFeedback,
      });
      setRecord(updated);
      setEditing(false);
    } catch (e) {
      alert(e.message);
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <div className="empty-state">불러오는 중…</div>;
  if (error) return <div className="error-msg">❌ {error}</div>;
  if (!record) return <div className="empty-state">기록을 찾을 수 없어요.</div>;

  const editableItems = itineraryItems.length > 0
    ? itineraryItems
    : (record.visitFeedback || []).map((f) => ({ itemId: f.itemId, placeName: f.placeName }));

  return (
    <div className="trip-record-detail-screen">
      <div className="trip-record-detail-head">
        <p className="trip-record-detail-date">
          📅 {formatFullDate(itineraryMeta?.startDate)}
          {itineraryMeta?.regionDisplayName ? ` — ${itineraryMeta.regionDisplayName}` : ''}
        </p>
        {!editing && (
          <button type="button" className="btn-edit-diary" onClick={handleStartEdit}>
            ✏️ 수정하기
          </button>
        )}
      </div>

      <div className="feedback-row overall-rating-row">
        <span className="feedback-name">이번 여행은?</span>
        {editing ? (
          <div className="feedback-ratings">
            {RATINGS.map((r) => (
              <button
                key={r.value}
                type="button"
                className={`rating-btn tagged ${overallRating === r.value ? 'selected' : ''}`}
                onClick={() => setOverallRating(r.value)}
                title={r.label}
                aria-label={r.label}
              >
                <span className="rating-icon">{r.icon}</span>
                <span className="rating-label">{r.label}</span>
              </button>
            ))}
          </div>
        ) : (
          <RatingBadge value={overallRating} />
        )}
      </div>

      {editing ? (
        <>
          <label className="trip-record-note-label" htmlFor="diary-note">
            한줄 의견 <span className="muted">(선택)</span>
          </label>
          <textarea
            id="diary-note"
            className="overall-note-input"
            value={overallNote}
            onChange={(e) => setOverallNote(e.target.value.slice(0, 500))}
            rows={3}
            maxLength={500}
          />
          <p className="trip-record-note-count">{overallNote.length}/500</p>
        </>
      ) : (
        <p className="trip-record-detail-note">
          {overallNote ? `"${overallNote}"` : <span className="muted">작성한 소감이 없어요.</span>}
        </p>
      )}

      {editableItems.length > 0 && (
        <>
          <p className="modal-desc feedback-section-label">장소별 기록</p>
          <div className="feedback-list">
            {editableItems.map((item) => (
              <div key={item.itemId} className="trip-record-detail-item">
                <div className="feedback-row">
                  <span className="feedback-name">{item.placeName}</span>
                  {editing ? (
                    <div className="feedback-ratings">
                      {RATINGS.map((r) => (
                        <button
                          key={r.value}
                          type="button"
                          className={`rating-btn compact ${ratings[item.itemId] === r.value ? 'selected' : ''} ${r.value === 'BAD' && ratings[item.itemId] === r.value ? 'bad' : ''}`}
                          onClick={() => setRating(item.itemId, r.value)}
                          title={r.label}
                          aria-label={r.label}
                        >
                          {r.icon}
                        </button>
                      ))}
                    </div>
                  ) : (
                    <RatingBadge value={ratings[item.itemId]} />
                  )}
                </div>
                {editing ? (
                  <input
                    type="text"
                    className="trip-record-item-memo-input"
                    placeholder="메모 (선택)"
                    value={memos[item.itemId] || ''}
                    onChange={(e) => setMemo(item.itemId, e.target.value.slice(0, 200))}
                    maxLength={200}
                  />
                ) : memos[item.itemId] ? (
                  <p className="trip-record-item-memo">{memos[item.itemId]}</p>
                ) : null}
              </div>
            ))}
          </div>
        </>
      )}

      {editing ? (
        <div className="trip-record-detail-actions">
          <button type="button" className="btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? '저장 중...' : '저장'}
          </button>
          <button type="button" className="btn-skip" onClick={handleCancelEdit} disabled={saving}>취소</button>
        </div>
      ) : (
        record.itineraryId && onViewItinerary && (
          <button type="button" className="btn-skip" onClick={() => onViewItinerary(record.itineraryId)}>
            이 일정 코스 다시 보기
          </button>
        )
      )}
    </div>
  );
}
