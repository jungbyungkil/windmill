import { useState } from 'react';
import RecommendationCard from './RecommendationCard';
import TrustBadge from './TrustBadge';
import TagGroupPicker from './TagGroupPicker';
import { BUDGET_OPTIONS } from '../constants';

export default function RecommendationSearch({ onSearch, onAdd, results, loading, addingId, pinnedPlaceName }) {
  const [query, setQuery] = useState('');
  const [tags, setTags] = useState([]);
  const [freeOnly, setFreeOnly] = useState(false);
  const [budget, setBudget] = useState(null);

  function toggleTag(tag) {
    setTags((prev) => prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]);
  }

  function toggleBudget(value) {
    setBudget((prev) => (prev === value ? null : value));
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ query, tags, maxBudgetPerPerson: budget });
  }

  const visibleResults = freeOnly ? (results || []).filter((c) => c.isFree) : results;

  return (
    <div className="reco-search">
      <h2 className="section-title">새로운 장소 추천받기</h2>
      <TrustBadge />
      <p className="place-name-search-hint">
        {pinnedPlaceName
          ? `📌 ${pinnedPlaceName} 근처로 추천해드리고 있어요. #실내·#맛집 같은 태그를 눌러보세요.`
          : '지금은 근처 추천 기능이 꺼져있어요. 장소를 📌 고정하면 그때부터 이 장소 근처 위주로 추천해드려요.'}
      </p>
      <form className="reco-search-form" onSubmit={handleSubmit}>
        <input
          type="text"
          className="reco-query-input"
          placeholder="예: 아이랑 갈만한 곳"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <TagGroupPicker selected={tags} onToggle={toggleTag} />
        <div className="reco-budget-row">
          <span className="reco-budget-label">💰 예산(1인 기준)</span>
          <div className="reco-tag-row">
            {BUDGET_OPTIONS.map((opt) => (
              <button
                type="button"
                key={opt.value}
                className={`tag ${budget === opt.value ? 'selected' : ''}`}
                onClick={() => toggleBudget(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
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
