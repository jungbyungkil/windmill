import { useState } from 'react';
import RecommendationCard from './RecommendationCard';

/**
 * 앵커(고정 일정) 등록 - 이미 시각이 정해진 장소(예: DDP 공연)를 이름으로 찾아 하루 일정의
 * 기준점으로 등록한다. 고르면 바로 담지 않고 시작 시각을 물어보는 모달로 이어진다.
 */
export default function PlaceNameSearch({ onSearch, onAdd, results, loading, addingId }) {
  const [query, setQuery] = useState('');

  function handleSubmit(e) {
    e.preventDefault();
    if (!query.trim()) return;
    onSearch(query.trim());
  }

  return (
    <div className="reco-search place-name-search">
      <h2 className="section-title">고정 일정(앵커) 등록</h2>
      <p className="place-name-search-hint">
        DDP 공연처럼 시각이 이미 정해진 장소가 있다면 이름으로 찾아 등록해 보세요.
        시작 시각만 정하면 앞뒤 빈 시간(식사·가벼운 관광)을 자동으로 채워드려요.
      </p>
      <form className="reco-search-form" onSubmit={handleSubmit}>
        <input
          type="text"
          className="reco-query-input"
          placeholder="예: DDP, 청룡사(안성)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button type="submit" className="btn-primary" disabled={loading || !query.trim()}>
          {loading ? '찾는 중...' : '🔎 이름으로 검색'}
        </button>
      </form>

      {results !== null && (
        results.length === 0 ? (
          <p className="empty-state">'{query}'(으)로 찾은 장소가 없어요. 다른 이름으로 검색해보세요.</p>
        ) : (
          <div className="reco-grid">
            {results.map((c) => (
              <RecommendationCard
                key={c.contentId}
                candidate={c}
                onAdd={onAdd}
                adding={addingId === c.contentId}
                addLabel="📌 이 장소를 앵커로 등록"
                addingLabel="여는 중..."
              />
            ))}
          </div>
        )
      )}
    </div>
  );
}
