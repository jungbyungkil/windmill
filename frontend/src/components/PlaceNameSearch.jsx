import { useState } from 'react';
import RecommendationCard from './RecommendationCard';

/** 가고 싶은 곳이 이미 정해진 사용자용 - 장소명으로 직접 검색해서 바로 담기 (예: "청룡사(안성)") */
export default function PlaceNameSearch({ onSearch, onAdd, results, loading, addingId }) {
  const [query, setQuery] = useState('');

  function handleSubmit(e) {
    e.preventDefault();
    if (!query.trim()) return;
    onSearch(query.trim());
  }

  return (
    <div className="reco-search place-name-search">
      <h2 className="section-title">가고 싶은 곳 검색해서 담기</h2>
      <p className="place-name-search-hint">이미 정해둔 장소가 있다면 이름으로 찾아 바로 담아보세요.</p>
      <form className="reco-search-form" onSubmit={handleSubmit}>
        <input
          type="text"
          className="reco-query-input"
          placeholder="예: 청룡사(안성)"
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
              />
            ))}
          </div>
        )
      )}
    </div>
  );
}
