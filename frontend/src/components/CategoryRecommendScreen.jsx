import { useEffect, useState } from 'react';
import * as api from '../api/windmillApi';

/**
 * 일정 생성 직후 우선 노출되는 API 기반 카테고리 추천 화면.
 * AI 스케줄보다 먼저 뜨며, TourAPI + 방문자(집중률) 순으로 정렬된 장소를 보여준다.
 */
export default function CategoryRecommendScreen({
  regionCode,
  excludeContentIds = [],
  onAdd,
  onContinue,
  onTryAi,
  addingId,
}) {
  const [groups, setGroups] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeCategory, setActiveCategory] = useState(null);
  const [addedIds, setAddedIds] = useState(() => new Set());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.getCategoryRecommendations({ regionCode, excludeContentIds })
      .then((data) => {
        if (cancelled) return;
        setGroups(data);
        setActiveCategory(data?.[0]?.category || null);
      })
      .catch((e) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [regionCode]); // eslint-disable-line react-hooks/exhaustive-deps

  async function handleAdd(place) {
    await onAdd?.(place);
    setAddedIds((prev) => new Set(prev).add(place.contentId));
  }

  const activeGroup = groups?.find((g) => g.category === activeCategory) || groups?.[0];

  return (
    <div className="category-reco-screen">
      <header className="category-reco-hero">
        <div className="category-reco-brand">바람따라</div>
        <h1 className="category-reco-title">여유로운 장소 둘러보기</h1>
        <p className="category-reco-sub">
          혼잡도가 낮은 곳부터 보여드려요. 스마트 동선 추천을 먼저 받아보셨다면, 여기서 더 골라보세요.
        </p>
      </header>

      {loading && (
        <div className="category-reco-loading">
          <div className="skeleton-card" />
          <div className="skeleton-card" />
          <div className="skeleton-card" />
        </div>
      )}

      {error && <div className="error-msg">❌ {error}</div>}

      {!loading && groups && (
        <>
          <nav className="category-tabs" aria-label="추천 카테고리">
            {groups.map((g) => (
              <button
                key={g.category}
                type="button"
                className={`category-tab ${activeCategory === g.category ? 'active' : ''}`}
                onClick={() => setActiveCategory(g.category)}
              >
                <span className="category-tab-label">{g.label}</span>
                <span className="category-tab-sub">{g.subLabel}</span>
              </button>
            ))}
          </nav>

          {activeGroup && (
            <section className="category-section">
              <div className="category-section-head">
                <h2>{activeGroup.label}</h2>
                <span className="category-count">{activeGroup.places?.length || 0}곳 · 여유로운 순</span>
              </div>

              {(activeGroup.places?.length || 0) === 0 ? (
                <p className="empty-state">이 카테고리 추천을 아직 찾지 못했어요.</p>
              ) : (
                <div className="category-place-grid">
                  {activeGroup.places.map((place) => {
                    const added = addedIds.has(place.contentId);
                    return (
                      <article key={place.contentId} className={`category-place-card ${added ? 'added' : ''}`}>
                        <div className="category-place-media">
                          {place.thumbnailUrl ? (
                            <img src={place.thumbnailUrl} alt={place.placeName} loading="lazy" />
                          ) : (
                            <div className="category-place-placeholder">🌬️</div>
                          )}
                          <span className="category-place-rank">#{place.rank}</span>
                          {place.crowdRate != null && (
                            <span className="category-place-popular">여유 {Math.round(100 - place.crowdRate)}%</span>
                          )}
                        </div>
                        <div className="category-place-body">
                          <h3>{place.placeName}</h3>
                          {place.oneLiner && <p>{place.oneLiner}</p>}
                          {place.addr1 && <div className="category-place-addr">{place.addr1}</div>}
                          <button
                            type="button"
                            className="btn-add"
                            disabled={added || addingId === place.contentId}
                            onClick={() => handleAdd(place)}
                          >
                            {added ? '담김' : addingId === place.contentId ? '담는 중...' : '+ 일정에 담기'}
                          </button>
                        </div>
                      </article>
                    );
                  })}
                </div>
              )}
            </section>
          )}
        </>
      )}

      <footer className="category-reco-actions">
        <button type="button" className="btn-primary" onClick={onContinue}>
          일정 화면으로 가기
        </button>
        <button type="button" className="btn-skip" onClick={onTryAi}>
          AI로 일정 짜보기
        </button>
      </footer>
    </div>
  );
}
