import { useEffect, useState } from 'react';
import * as api from '../api/windmillApi';
import { COMPANION_TYPE_OPTIONS } from '../constants';

const COMPANION_LABEL = Object.fromEntries(COMPANION_TYPE_OPTIONS.map((o) => [o.value, o.label]));
const LIKED_KEY = 'windmill.likedTripStories';

function loadLikedIds() {
  try {
    const raw = localStorage.getItem(LIKED_KEY);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch {
    return new Set();
  }
}

function saveLikedIds(ids) {
  localStorage.setItem(LIKED_KEY, JSON.stringify([...ids]));
}

function formatDateRange(start, end) {
  if (!start) return null;
  return start === end ? start : `${start} ~ ${end}`;
}

/**
 * 당일치기 추천 기록 피드.
 * 여행 지역이 있으면 그 지역을 맨 앞에 두고, 평점·좋아요·클릭 순으로 Top 5.
 * 카드/CTA 클릭 시 해당 일정 그대로 당일치기 시작.
 */
export default function TripStoryFeed({
  signguFullCode,
  regionLabel,
  tripDate,
  onStartFromStory,
  startingStoryId,
  startDisabled,
}) {
  const [stories, setStories] = useState([]);
  const [loading, setLoading] = useState(false);
  const [likedIds, setLikedIds] = useState(() => loadLikedIds());
  const [expandedId, setExpandedId] = useState(null);
  const [likingId, setLikingId] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setExpandedId(null);
    api.getTripStoryFeed(signguFullCode || undefined)
      .then((list) => { if (!cancelled) setStories(list || []); })
      .catch(() => { if (!cancelled) setStories([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [signguFullCode]);

  if (!loading && stories.length === 0) return null;

  async function handleExpand(e, story) {
    e.stopPropagation();
    setExpandedId((prev) => (prev === story.id ? null : story.id));
    try {
      const updated = await api.clickTripStory(story.id);
      setStories((prev) => prev.map((s) => (s.id === story.id ? { ...s, ...updated } : s)));
    } catch {
      // 조회수 실패는 조용히 무시
    }
  }

  function handleStart(e, story) {
    e.stopPropagation();
    if (!onStartFromStory || startDisabled || startingStoryId) return;
    onStartFromStory(story, tripDate);
  }

  async function handleLike(e, story) {
    e.stopPropagation();
    if (likedIds.has(story.id) || likingId === story.id) return;
    setLikingId(story.id);
    try {
      const updated = await api.likeTripStory(story.id);
      setStories((prev) => {
        const next = prev.map((s) => (s.id === story.id ? { ...s, ...updated } : s));
        return [...next].sort((a, b) => {
          const aMatch = signguFullCode && a.signguFullCode === signguFullCode ? 0 : 1;
          const bMatch = signguFullCode && b.signguFullCode === signguFullCode ? 0 : 1;
          if (aMatch !== bMatch) return aMatch - bMatch;
          if ((b.likeCount || 0) !== (a.likeCount || 0)) return (b.likeCount || 0) - (a.likeCount || 0);
          return (b.clickCount || 0) - (a.clickCount || 0);
        });
      });
      const next = new Set(likedIds);
      next.add(story.id);
      setLikedIds(next);
      saveLikedIds(next);
    } finally {
      setLikingId(null);
    }
  }

  const sub = regionLabel
    ? `${regionLabel} 당일치기를 평점·좋아요·조회 순으로 보여 드려요 · 카드를 누르면 그 일정 그대로 시작`
    : '하루 일정으로 다녀온 여행자들의 참고 Top 5 · 카드를 누르면 그 일정 그대로 시작';

  return (
    <section className="trip-story-feed" aria-label="당일치기 추천 기록">
      <div className="trip-story-feed-head">
        <h2 className="trip-story-feed-title">당일치기 추천 기록</h2>
        <p className="trip-story-feed-sub">{sub}</p>
      </div>

      {loading ? (
        <div className="trip-story-loading">추천 기록을 불러오는 중…</div>
      ) : (
        <div className="trip-story-list" role="list">
          {stories.map((story, index) => {
            const expanded = expandedId === story.id;
            const liked = likedIds.has(story.id);
            const adapted = (story.rerouteCount || 0) > 0 || (story.alternatePlaceCount || 0) > 0;
            const regionMatch = Boolean(signguFullCode && story.signguFullCode === signguFullCode);
            const starting = startingStoryId === story.id;
            return (
              <article
                key={story.id}
                className={`trip-story-card ${expanded ? 'expanded' : ''} ${regionMatch ? 'region-match' : ''} ${starting ? 'starting' : ''}`}
                onClick={(e) => handleStart(e, story)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    handleStart(e, story);
                  }
                }}
                aria-label={`${story.placeNames?.slice(0, 2).join(' · ') || '추천 일정'}으로 당일치기 시작`}
              >
                <div className="trip-story-media">
                  {story.thumbnailUrl ? (
                    <img src={story.thumbnailUrl} alt="" loading="lazy" />
                  ) : (
                    <div className="trip-story-placeholder">🌬️</div>
                  )}
                  <span className="trip-story-rank">#{index + 1}</span>
                  {regionMatch && index === 0 && (
                    <span className="trip-story-region-badge">이 지역 Top</span>
                  )}
                  {adapted && (
                    <span className="trip-story-adapt" title="변수에 대응해 일정을 바꿨어요">
                      변수 대응 {story.rerouteCount || story.alternatePlaceCount}
                    </span>
                  )}
                </div>

                <div className="trip-story-body">
                  <div className="trip-story-meta">
                    {story.regionDisplayName && (
                      <span className="trip-story-chip">{story.regionDisplayName}</span>
                    )}
                    {formatDateRange(story.startDate, story.endDate) && (
                      <span className="trip-story-chip">🗓️ {formatDateRange(story.startDate, story.endDate)}</span>
                    )}
                    {story.companionType && COMPANION_LABEL[story.companionType] && (
                      <span className="trip-story-chip">{COMPANION_LABEL[story.companionType]}</span>
                    )}
                    {adapted && (
                      <span className="trip-story-chip adapt">대안 {story.alternatePlaceCount || 0}곳</span>
                    )}
                  </div>

                  {story.placeNames?.length > 0 && (
                    <p className="trip-story-places">
                      {expanded ? story.placeNames.join(' · ') : story.placeNames.slice(0, 3).join(' · ')}
                      {!expanded && story.placeNames.length > 3 ? ' …' : ''}
                    </p>
                  )}

                  {story.overallNote && (
                    <p className={`trip-story-note ${expanded ? '' : 'clamped'}`}>
                      "{story.overallNote}"
                    </p>
                  )}

                  <div className="trip-story-stats">
                    <button
                      type="button"
                      className={`trip-story-like ${liked ? 'liked' : ''}`}
                      onClick={(e) => handleLike(e, story)}
                      disabled={liked || likingId === story.id}
                      aria-label="좋아요"
                    >
                      {liked ? '❤️' : '🤍'} {story.likeCount ?? 0}
                    </button>
                    <span className="trip-story-clicks">👀 {story.clickCount ?? 0}</span>
                    <button
                      type="button"
                      className="trip-story-detail"
                      onClick={(e) => handleExpand(e, story)}
                    >
                      {expanded ? '접기' : '더보기'}
                    </button>
                  </div>

                  <button
                    type="button"
                    className="trip-story-start"
                    onClick={(e) => handleStart(e, story)}
                    disabled={startDisabled || Boolean(startingStoryId)}
                  >
                    {starting ? '일정 불러오는 중…' : '이 일정으로 시작'}
                  </button>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
