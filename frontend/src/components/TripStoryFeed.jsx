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
 * 첫 화면 인기 여행 기록 피드.
 * 좋아요 → 클릭 순으로 최대 5건을 보여 흥미를 유도한다.
 */
export default function TripStoryFeed() {
  const [stories, setStories] = useState([]);
  const [likedIds, setLikedIds] = useState(() => loadLikedIds());
  const [expandedId, setExpandedId] = useState(null);
  const [likingId, setLikingId] = useState(null);

  useEffect(() => {
    let cancelled = false;
    api.getTripStoryFeed()
      .then((list) => { if (!cancelled) setStories(list || []); })
      .catch(() => { if (!cancelled) setStories([]); });
    return () => { cancelled = true; };
  }, []);

  if (stories.length === 0) return null;

  async function handleOpen(story) {
    setExpandedId((prev) => (prev === story.id ? null : story.id));
    try {
      const updated = await api.clickTripStory(story.id);
      setStories((prev) => prev.map((s) => (s.id === story.id ? { ...s, ...updated } : s)));
    } catch {
      // 조회수 실패는 조용히 무시
    }
  }

  async function handleLike(e, story) {
    e.stopPropagation();
    if (likedIds.has(story.id) || likingId === story.id) return;
    setLikingId(story.id);
    try {
      const updated = await api.likeTripStory(story.id);
      setStories((prev) => prev.map((s) => (s.id === story.id ? { ...s, ...updated } : s)));
      const next = new Set(likedIds);
      next.add(story.id);
      setLikedIds(next);
      saveLikedIds(next);
    } finally {
      setLikingId(null);
    }
  }

  return (
    <section className="trip-story-feed" aria-label="다른 여행자들의 기록">
      <div className="trip-story-feed-head">
        <h2 className="trip-story-feed-title">바람따라 여행 기록</h2>
        <p className="trip-story-feed-sub">좋아요·관심 많은 여행 스토리 Top 5</p>
      </div>

      <div className="trip-story-list">
        {stories.map((story, index) => {
          const expanded = expandedId === story.id;
          const liked = likedIds.has(story.id);
          return (
            <article
              key={story.id}
              className={`trip-story-card ${expanded ? 'expanded' : ''}`}
              onClick={() => handleOpen(story)}
            >
              <div className="trip-story-media">
                {story.thumbnailUrl ? (
                  <img src={story.thumbnailUrl} alt="" loading="lazy" />
                ) : (
                  <div className="trip-story-placeholder">🌬️</div>
                )}
                <span className="trip-story-rank">#{index + 1}</span>
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
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}
