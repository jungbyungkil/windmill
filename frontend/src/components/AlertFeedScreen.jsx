import { useEffect, useState } from 'react';
import * as api from '../api/windmillApi';

const LEVEL_TONE = { NORMAL: 'normal', WARNING: 'warning', DANGER: 'danger' };

/**
 * 서버 시각 파싱.
 * - 오프셋/Z가 있으면 그대로 Instant로 읽는다 (신규: 2026-09-04T14:25:00+09:00).
 * - 없으면 UTC 벽시계로 본다. 구버전 API가 LocalDateTime을 타임존 없이 보냈고,
 *   Render는 UTC라 브라우저가 KST로 해석하면 9시간 전으로 표시됐다.
 */
export function parseAlertTime(createdAt) {
  if (!createdAt) return null;
  const raw = String(createdAt).trim();
  if (!raw) return null;
  const iso = /[zZ]|[+-]\d{2}:\d{2}$/.test(raw) ? raw : `${raw}Z`;
  const then = new Date(iso);
  return Number.isNaN(then.getTime()) ? null : then;
}

export function formatRelativeTime(createdAt, now = Date.now()) {
  const then = parseAlertTime(createdAt);
  if (!then) return '';
  const minutes = Math.max(0, Math.floor((now - then.getTime()) / 60000));
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  return `${days}일 전`;
}

/** 알림 - 실제로 발송된 알림 이력을 최신순 리스트로. NudgeCard와 달리 읽기 전용(닫기 없음). */
export default function AlertFeedScreen({ itineraryId, showTitle = true }) {
  const [alerts, setAlerts] = useState(null); // null = 로딩 중
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!itineraryId) return;
    setAlerts(null);
    setError(null);
    api.getAlertFeed(itineraryId, { limit: 30 })
      .then(setAlerts)
      .catch((e) => setError(e.message || '알림을 불러오지 못했어요'));
  }, [itineraryId]);

  return (
    <div className="alert-feed">
      {showTitle && <h2 className="section-title">알림</h2>}
      {error && <p className="error-msg">{error}</p>}
      {!error && alerts === null && <p className="empty-state">불러오는 중...</p>}
      {!error && alerts !== null && alerts.length === 0 && (
        <p className="empty-state">아직 온 알림이 없어요.</p>
      )}
      {alerts !== null && alerts.length > 0 && (
        <ul className="alert-feed-list">
          {alerts.map((a) => (
            <li key={a.id} className={`alert-feed-card tone-${LEVEL_TONE[a.level] || 'normal'}`}>
              <span className="alert-feed-pip" aria-hidden="true" />
              <div className="alert-feed-body">
                <strong className="alert-feed-headline">{a.headline}</strong>
                {a.detail && <p className="alert-feed-detail">{a.detail}</p>}
                <span className="alert-feed-time">{formatRelativeTime(a.createdAt)}</span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
