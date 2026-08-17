import { useEffect, useState } from 'react';
import * as api from '../api/windmillApi';
import BaramiBubble from './BaramiBubble';
import TrustBadge from './TrustBadge';

const ICON_GRID_CATEGORIES = new Set(['RESTAURANT', 'CAFE', '음식점', '카페', '맛집', '식당']);

function isIconGridCategory(group) {
  if (!group) return false;
  const key = String(group.category || '');
  const label = String(group.label || '');
  if (ICON_GRID_CATEGORIES.has(key) || ICON_GRID_CATEGORIES.has(label)) return true;
  return /음식|맛집|카페|식당|레스토랑/.test(label);
}

/**
 * 일정 생성 직후 우선 노출되는 API 기반 카테고리 추천 화면.
 * 맛집·카페는 대표 먹거리형 원형 썸네일 그리드로 보여 준다.
 */
export default function CategoryRecommendScreen({
  regionCode,
  excludeContentIds = [],
  childAges = [],
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
    api.getCategoryRecommendations({ regionCode, excludeContentIds, childAges })
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
  const iconGrid = isIconGridCategory(activeGroup);

  return (
    <div className="category-reco-screen">
      <header className="category-reco-hero">
        <div className="category-reco-brand">바람따라</div>
        <h1 className="category-reco-title">여유로운 장소 둘러보기</h1>
        <p className="category-reco-sub">
          혼잡도가 낮은 곳부터 보여드려요. 스마트 동선 추천을 먼저 받아보셨다면, 여기서 더 골라보세요.
        </p>
        <TrustBadge />
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
              ) : iconGrid ? (
                <div className="category-icon-grid" role="list">
                  {activeGroup.places.map((place) => {
                    const added = addedIds.has(place.contentId);
                    return (
                      <article
                        key={place.contentId}
                        className={`category-icon-card ${added ? 'added' : ''}`}
                        role="listitem"
                      >
                        <button
                          type="button"
                          className="category-icon-hit"
                          disabled={added || addingId === place.contentId}
                          onClick={() => handleAdd(place)}
                          aria-label={`${place.placeName} 일정에 담기`}
                        >
                          <span className="category-icon-thumb">
                            {place.thumbnailUrl ? (
                              <img src={place.thumbnailUrl} alt="" loading="lazy" />
                            ) : (
                              <span className="category-icon-placeholder">🍽️</span>
                            )}
                          </span>
                          <span className="category-icon-name">{place.placeName}</span>
                          {place.crowdRate != null && (
                            <span className="category-icon-meta">여유 {Math.round(100 - place.crowdRate)}%</span>
                          )}
                          <span className="category-icon-cta">
                            {added ? '담김' : addingId === place.contentId ? '…' : '+ 담기'}
                          </span>
                        </button>
                      </article>
                    );
                  })}
                </div>
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

          <BaramiBubble comment="취향에 맞는 곳을 골라 담아 보세요. 담은 장소는 오늘 일정에 바로 붙어요!" compact />
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
