import { useState } from 'react';

const RATINGS = [
  { value: 'GOOD', icon: '👍' },
  { value: 'NEUTRAL', icon: '😐' },
  { value: 'BAD', icon: '👎' },
];

export default function TripRecordModal({ open, items, submitting, onSubmit, onClose }) {
  const [ratings, setRatings] = useState({});
  const [overallNote, setOverallNote] = useState('');

  if (!open) return null;

  function setRating(itemId, rating) {
    setRatings((prev) => ({ ...prev, [itemId]: rating }));
  }

  function handleSubmit() {
    const visitFeedback = items
      .filter((item) => ratings[item.itemId])
      .map((item) => ({ itemId: item.itemId, placeName: item.placeName, rating: ratings[item.itemId] }));
    onSubmit({ overallNote, visitFeedback });
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-panel trip-record-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>🏁 여행 마무리</h3>
          <button className="icon-btn" onClick={onClose}>✕</button>
        </div>

        <p className="modal-desc">다녀온 곳은 어땠나요? 다음 바람따라 추천에 반영할게요.</p>

        <div className="feedback-list">
          {items.map((item) => (
            <div key={item.itemId} className="feedback-row">
              <span className="feedback-name">{item.placeName}</span>
              <div className="feedback-ratings">
                {RATINGS.map((r) => (
                  <button
                    key={r.value}
                    type="button"
                    className={`rating-btn ${ratings[item.itemId] === r.value ? 'selected' : ''}`}
                    onClick={() => setRating(item.itemId, r.value)}
                  >
                    {r.icon}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>

        <textarea
          className="overall-note-input"
          placeholder="여행 전체 소감을 남겨주세요 (선택)"
          value={overallNote}
          onChange={(e) => setOverallNote(e.target.value)}
        />

        <button className="btn-primary" onClick={handleSubmit} disabled={submitting}>
          {submitting ? '저장 중...' : '✅ 여행 기록 저장하기'}
        </button>
      </div>
    </div>
  );
}
