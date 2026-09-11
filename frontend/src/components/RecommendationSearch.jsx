import { useEffect, useState } from 'react';
import RecommendationCard from './RecommendationCard';
import TagGroupPicker from './TagGroupPicker';
import { BUDGET_OPTIONS, isFoodSearch } from '../constants';

export default function RecommendationSearch({
  onSearch,
  onAdd,
  results,
  loading,
  addingId,
  originPlaces = [],
  defaultOriginItemId = '',
}) {
  const [tags, setTags] = useState([]);
  const [freeOnly, setFreeOnly] = useState(false);
  const [budget, setBudget] = useState(null);
  const [originItemId, setOriginItemId] = useState(defaultOriginItemId);
  const originIdsKey = originPlaces.map((p) => p.itemId).join(',');

  useEffect(() => {
    const ids = originIdsKey.split(',').filter(Boolean);
    setOriginItemId((current) => {
      if (current && ids.includes(String(current))) return String(current);
      if (defaultOriginItemId && ids.includes(String(defaultOriginItemId))) {
        return String(defaultOriginItemId);
      }
      return ids[0] || '';
    });
  }, [originIdsKey, defaultOriginItemId]);

  const foodSearch = isFoodSearch({ tags });
  const selectedOrigin = originPlaces.find((p) => String(p.itemId) === String(originItemId));

  function toggleTag(tag) {
    setTags((prev) => prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]);
  }

  function toggleBudget(value) {
    setBudget((prev) => (prev === value ? null : value));
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({
      tags,
      maxBudgetPerPerson: foodSearch ? budget : null,
      originContentId: selectedOrigin?.contentId,
      originContentTypeId: selectedOrigin?.contentTypeId,
    });
  }

  // 가격 정보가 없는(isFree === null) 곳은 무료로 간주한다. 음식점은 이 체크박스 자체가
  // foodSearch일 때 숨겨지므로(항상 유료 취급) 여기 남는 후보(산책로·공원·문화시설 등)는
  // 정보 없음 = 유료로 단정할 근거가 없어 무료 쪽에 포함하는 게 사용자 기대와 맞다(2026-09-11 제보).
  const visibleResults = (!foodSearch && freeOnly)
    ? (results || []).filter((c) => c.isFree !== false)
    : results;

  return (
    <div className="reco-search">
      <h2 className="section-title">새로운 장소 추천받기</h2>
      <p className="place-name-search-hint">
        {selectedOrigin
          ? `${selectedOrigin.placeName} 근처를 우선해요. 아래 기준 장소를 바꾸면 다른 곳 근처를 볼 수 있어요.`
          : '일정에 담긴 장소가 없으면 지역 전체에서 찾아요. 태그를 고른 뒤 추천받기를 누르세요.'}
      </p>
      <form className="reco-search-form" onSubmit={handleSubmit}>
        {originPlaces.length > 0 && (
          <label className="reco-origin-field">
            <span className="reco-budget-label">근처 기준</span>
            <select
              className="reco-origin-select"
              value={originItemId}
              onChange={(e) => setOriginItemId(e.target.value)}
            >
              {originPlaces.map((place) => (
                <option key={place.itemId} value={String(place.itemId)}>
                  {place.scheduledTime ? `${place.scheduledTime} · ` : ''}
                  {place.placeName}
                  {place.pinned ? ' · 고정' : ''}
                </option>
              ))}
            </select>
          </label>
        )}
        <TagGroupPicker selected={tags} onToggle={toggleTag} />
        {foodSearch && (
          <div className="reco-budget-row">
            <span className="reco-budget-label">식사 참고 · 1인 기준</span>
            <p className="reco-budget-hint">대략적인 눈금이에요. 실제 메뉴와 다를 수 있어요.</p>
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
        )}
        {!foodSearch && (
          <label className="trip-form-checkbox reco-free-filter">
            <input type="checkbox" checked={freeOnly} onChange={(e) => setFreeOnly(e.target.checked)} />
            🎫 무료 장소만 보기
          </label>
        )}

        <button type="submit" className="btn-primary" disabled={loading}>
          {loading ? '찾는 중...' : '🔍 추천받기'}
        </button>
      </form>

      {results !== null && (
        visibleResults.length === 0 ? (
          <p className="empty-state">
            {!foodSearch && freeOnly
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
