import { useState } from 'react';
import RecommendationCard from './RecommendationCard';
import TrustBadge from './TrustBadge';
import { TAG_OPTIONS } from '../constants';

export default function RecommendationSearch({ onSearch, onAdd, results, loading, addingId }) {
  const [query, setQuery] = useState('');
  const [tags, setTags] = useState([]);
  const [freeOnly, setFreeOnly] = useState(false);

  function toggleTag(tag) {
    setTags((prev) => prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]);
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ query, tags });
  }

  const visibleResults = freeOnly ? (results || []).filter((c) => c.isFree) : results;

  return (
    <div className="reco-search">
      <h2 className="section-title">새로운 장소 추천받기</h2>
      <TrustBadge />
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
        <label className="trip-form-checkbox reco-free-filter">
          <input type="checkbox" checked={freeOnly} onChange={(e) => setFreeOnly(e.target.checked)} />
          🎫 무료 장소만 보기
        </label>

        <button type="submit" className="btn-primary" disabled={loading}>
          {loading ? '찾는 중...' : '🔍 추천받기'}
        </button>
      </form>

      {results !== null && (
        visibleResults.length === 0 ? (
          <p className="empty-state">
            {freeOnly
              ? '무료 장소가 없어요. 필터를 해제해보세요.'
              : '조건에 맞는 추천 결과가 없어요. 태그(#자연·#실내·#맛집·#아이동반·#액티비티·#역사)로 다시 찾아보세요.'}
          </p>
        ) : (
          <div className="reco-grid">
            {visibleResults.map((c, i) => (
              <RecommendationCard
                key={c.contentId}
                candidate={c}
                onAdd={onAdd}
                adding={addingId === c.contentId}
                nextCandidates={visibleResults.slice(i + 1, i + 3)}
              />
            ))}
          </div>
        )
      )}
    </div>
  );
}
