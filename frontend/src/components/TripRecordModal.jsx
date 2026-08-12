import { useState } from 'react';
import useModalHistory from '../hooks/useModalHistory';

/** 간편 태깅 3종 - 후기 대신 아이콘으로 기록 → 추천 정확도(BAD 제외)에 반영 */
const RATINGS = [
  { value: 'GOOD', icon: '👍', label: '좋았음' },
  { value: 'NEUTRAL', icon: '😐', label: '보통' },
  { value: 'BAD', icon: '👎', label: '별로' },
];

export default function TripRecordModal({ open, items, submitting, onSubmit, onClose }) {
  const [ratings, setRatings] = useState({});
  const [overallRating, setOverallRating] = useState(null);
  const [overallNote, setOverallNote] = useState('');

  useModalHistory(open, onClose);

  if (!open) return null;

  function setRating(itemId, rating) {
    setRatings((prev) => ({ ...prev, [itemId]: rating }));
  }

  const taggedCount = Object.keys(ratings).length;
  const canSubmit = Boolean(overallRating);

  function handleSubmit() {
    if (!canSubmit) return;
    const visitFeedback = items
      .filter((item) => ratings[item.itemId])
      .map((item) => ({ itemId: item.itemId, placeName: item.placeName, rating: ratings[item.itemId] }));
    onSubmit({
      overallRating,
      overallNote: overallNote.trim(),
      visitFeedback,
    });
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel trip-record-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>간편 기록</h3>
          <button className="icon-btn" onClick={onClose} type="button">✕</button>
        </div>

        <p className="modal-desc">
          아이콘만 눌러 주세요. &quot;별로&quot;로 표시한 장소는 다음 추천에서 빼 드려요.
        </p>

        <div className="feedback-row overall-rating-row">
          <span className="feedback-name">이번 여행은?</span>
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
        </div>
        {overallRating === 'GOOD' && (
          <p className="modal-desc overall-rating-hint">
            같은 지역으로 떠나는 다른 여행자에게도 이 일정을 보여드릴게요.
          </p>
        )}

        <label className="trip-record-note-label" htmlFor="trip-overall-note">
          한줄 의견 <span className="muted">(선택)</span>
        </label>
        <textarea
          id="trip-overall-note"
          className="overall-note-input"
          value={overallNote}
          onChange={(e) => setOverallNote(e.target.value.slice(0, 500))}
          placeholder="예: 아이들과 당일치기로 좋아요. 비 올 때 실내 전환이 좋았어요."
          rows={3}
          maxLength={500}
        />
        <p className="trip-record-note-count">{overallNote.length}/500</p>

        {items.length > 0 && (
          <>
            <p className="modal-desc feedback-section-label">
              장소별 태깅 <span className="muted">({taggedCount}/{items.length})</span>
            </p>
            <div className="feedback-list">
              {items.map((item) => (
                <div key={item.itemId} className="feedback-row">
                  <span className="feedback-name">{item.placeName}</span>
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
                </div>
              ))}
            </div>
          </>
        )}

        <button className="btn-primary" type="button" onClick={handleSubmit} disabled={submitting || !canSubmit}>
          {submitting ? '저장 중...' : '기록 저장'}
        </button>
      </div>
    </div>
  );
}
