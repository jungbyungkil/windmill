import { useState } from 'react';
import RecommendationCard from './RecommendationCard';
import { TAG_OPTIONS } from '../constants';

export default function RecommendationSearch({ onSearch, onAdd, results, loading, addingId }) {
  const [query, setQuery] = useState('');
  const [tags, setTags] = useState([]);

  function toggleTag(tag) {
    setTags((prev) => prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]);
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ query, tags });
  }

  return (
    <div className="reco-search">
      <h2 className="section-title">새로운 장소 추천받기</h2>
      <form className="reco-search-form" onSubmit={handleSubmit}>
        <input
          type="text"
          className="reco-query-input"
          placeholder="예: 아이랑 갈만한 곳"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="reco-tag-row">
          {TAG_OPTIONS.map((tag) => (
            <button
              type="button"
              key={tag}
              className={`tag ${tags.includes(tag) ? 'selected' : ''}`}
              onClick={() => toggleTag(tag)}
            >
              {tag}
            </button>
          ))}
        </div>
        <button type="submit" className="btn-primary" disabled={loading}>
          {loading ? '찾는 중...' : '🔍 추천받기'}
        </button>
      </form>

      {results !== null && (
        results.length === 0 ? (
          <p className="empty-state">조건에 맞는 추천 결과가 없어요. 다른 태그로 시도해보세요.</p>
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
