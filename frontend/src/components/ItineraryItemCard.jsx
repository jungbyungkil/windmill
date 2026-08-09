import { useEffect, useState } from 'react';
import { TAG_OPTIONS } from '../constants';
import { itemStatusLevel, STATUS_LABEL, isIndoorPlace } from '../utils/statusLevel';

function normalizeTime(raw) {
  const value = (raw || '').trim();
  if (!value) return '';
  const m = value.match(/^(\d{1,2}):?(\d{0,2})$/);
  if (!m) return value.replace(/[^\d:]/g, '').slice(0, 5);
  const hh = Math.min(23, Number(m[1]));
  const mm = Math.min(59, Number(m[2] || '0'));
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

function draftFromItem(src) {
  return {
    placeName: src.placeName || '',
    scheduledTime: src.scheduledTime || '',
    tags: [...(src.tags || [])],
    addr1: src.addr1 || '',
    tel: src.tel || '',
    useFeeText: src.useFeeText || '',
    isFree: Boolean(src.isFree),
    restDateText: src.restDateText || '',
  };
}

/**
 * 일정 카드 - 정상·주의·긴급 상태 컬러로 시간 뱃지·테두리를 맞춘다.
 */
export default function ItineraryItemCard({
  item,
  weatherAlerted = false,
  businessAlerted = false,
  crowdAlerted = false,
  /** @deprecated */
  alerted = false,
  onUpdateTime,
  onUpdateItem,
  onTogglePin,
  onDelete,
  onOpenDocent,
}) {
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [draft, setDraft] = useState(() => draftFromItem(item));
  const isWeather = (weatherAlerted || alerted) && !isIndoorPlace(item);
  const status = itemStatusLevel(item, {
    weatherAlerted: isWeather,
    businessAlerted,
    crowdAlerted,
  });
  const statusClass = `status-${status.toLowerCase()}`;

  useEffect(() => {
    if (!editing) {
      setDraft(draftFromItem(item));
    }
  }, [item, editing]);

  function toggleTag(tag) {
    setDraft((prev) => ({
      ...prev,
      tags: prev.tags.includes(tag)
        ? prev.tags.filter((t) => t !== tag)
        : [...prev.tags, tag],
    }));
  }

  async function handleSave() {
    if (!draft.placeName.trim()) return;
    setSaving(true);
    try {
      const scheduledTime = normalizeTime(draft.scheduledTime);
      await onUpdateItem?.(item.itemId, {
        placeName: draft.placeName.trim(),
        scheduledTime: scheduledTime || null,
        tags: draft.tags,
        addr1: draft.addr1.trim(),
        tel: draft.tel.trim(),
        useFeeText: draft.isFree ? '' : draft.useFeeText.trim(),
        isFree: draft.isFree,
        restDateText: draft.restDateText.trim(),
      });
      setEditing(false);
    } finally {
      setSaving(false);
    }
  }

  function handleCancel() {
    setDraft(draftFromItem(item));
    setEditing(false);
  }

  return (
    <div
      className={`item-card ${statusClass} ${item.pinned ? 'pinned' : ''} ${isWeather ? 'weather-affected' : ''} ${businessAlerted ? 'business-affected' : ''} ${editing ? 'editing' : ''}`}
      data-status={status}
    >
      <span className={`item-status-rail ${statusClass}`} title={STATUS_LABEL[status]} aria-hidden="true" />
      {item.thumbnailUrl ? (
        <img className="item-thumb" src={item.thumbnailUrl} alt={item.placeName} loading="lazy" />
      ) : (
        <div className="item-thumb item-thumb-placeholder">🌬️</div>
      )}

      {!editing ? (
        <input
          type="text"
          className={`item-time ${statusClass}`}
          inputMode="numeric"
          placeholder="09:00"
          aria-label="방문 시각"
          maxLength={5}
          value={item.scheduledTime || ''}
          onChange={(e) => {
            const next = e.target.value.replace(/[^\d:]/g, '').slice(0, 5);
            onUpdateTime(item.itemId, next);
          }}
          onBlur={(e) => {
            const normalized = normalizeTime(e.target.value);
            if (normalized) onUpdateTime(item.itemId, normalized);
          }}
        />
      ) : (
        <input
          type="text"
          className={`item-time ${statusClass}`}
          inputMode="numeric"
          placeholder="09:00"
          aria-label="방문 시각 수정"
          maxLength={5}
          value={draft.scheduledTime}
          onChange={(e) => setDraft((prev) => ({
            ...prev,
            scheduledTime: e.target.value.replace(/[^\d:]/g, '').slice(0, 5),
          }))}
        />
      )}

      <div className="item-body">
        {!editing ? (
          <>
            <div className="item-head">
              <span className="item-name">{item.placeName}</span>
              <span className={`item-status-chip ${statusClass}`}>{STATUS_LABEL[status]}</span>
              {item.isAlternate && <span className="alt-badge" title="추천으로 담은 장소">추천</span>}
              {isWeather && <span className="weather-affected-badge" title="비·폭염 영향 야외 일정">⚠️ 야외</span>}
              {businessAlerted && <span className="business-affected-badge" title="방문일 정기휴무·영업종료">🚫 휴무</span>}
              {crowdAlerted && !isWeather && !businessAlerted && (
                <span className="crowd-affected-badge" title="혼잡 주의">👥 혼잡</span>
              )}
              {item.pinned && <span className="pin-badge" title={item.pinnedReason || '고정됨'}>📌</span>}
            </div>

            {item.tags?.length > 0 && (
              <div className="item-tags">
                {item.tags.map((t) => <span key={t} className="tag-chip">{t}</span>)}
              </div>
            )}

            <div className="reco-info">
              {item.addr1 && <div className="reco-info-row">📍 {item.addr1}</div>}
              {(item.isFree || item.useFeeText) && (
                <div className="reco-info-row">🎫 {item.isFree ? '무료' : item.useFeeText}</div>
              )}
              {item.tel && <div className="reco-info-row">☎️ {item.tel}</div>}
              {item.restDateText && <div className="reco-info-row reco-restdate">🚫 정기휴무: {item.restDateText}</div>}
            </div>

            {item.crowdRate !== null && item.crowdRate !== undefined && (
              <div className={`item-crowd ${statusClass}`}>혼잡도 {Math.round(item.crowdRate)}%</div>
            )}
          </>
        ) : (
          <div className="item-edit-form">
            <label className="item-edit-label">
              장소명
              <input
                type="text"
                value={draft.placeName}
                onChange={(e) => setDraft((prev) => ({ ...prev, placeName: e.target.value }))}
                maxLength={80}
                required
              />
            </label>

            <div className="item-edit-label">
              태그
              <div className="reco-tag-row item-edit-tags">
                {TAG_OPTIONS.map((tag) => (
                  <button
                    key={tag}
                    type="button"
                    className={`tag ${draft.tags.includes(tag) ? 'selected' : ''}`}
                    onClick={() => toggleTag(tag)}
                  >
                    {tag}
                  </button>
                ))}
              </div>
            </div>

            <label className="item-edit-label">
              주소
              <input
                type="text"
                value={draft.addr1}
                onChange={(e) => setDraft((prev) => ({ ...prev, addr1: e.target.value }))}
                placeholder="주소"
              />
            </label>

            <label className="item-edit-label">
              전화
              <input
                type="text"
                value={draft.tel}
                onChange={(e) => setDraft((prev) => ({ ...prev, tel: e.target.value }))}
                placeholder="전화번호"
              />
            </label>

            <label className="trip-form-checkbox item-edit-free">
              <input
                type="checkbox"
                checked={draft.isFree}
                onChange={(e) => setDraft((prev) => ({ ...prev, isFree: e.target.checked }))}
              />
              무료 입장
            </label>

            {!draft.isFree && (
              <label className="item-edit-label">
                이용요금
                <input
                  type="text"
                  value={draft.useFeeText}
                  onChange={(e) => setDraft((prev) => ({ ...prev, useFeeText: e.target.value }))}
                  placeholder="예: 성인 3,000원"
                />
              </label>
            )}

            <label className="item-edit-label">
              정기휴무
              <input
                type="text"
                value={draft.restDateText}
                onChange={(e) => setDraft((prev) => ({ ...prev, restDateText: e.target.value }))}
                placeholder="예: 매주 월요일"
              />
            </label>

            <div className="item-edit-actions">
              <button type="button" className="btn-primary" onClick={handleSave} disabled={saving || !draft.placeName.trim()}>
                {saving ? '저장 중...' : '저장'}
              </button>
              <button type="button" className="btn-secondary" onClick={handleCancel} disabled={saving}>
                취소
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="item-actions">
        {!editing && (
          <button
            className="icon-btn"
            title="수정"
            onClick={() => setEditing(true)}
          >
            ✏️
          </button>
        )}
        <button
          className="icon-btn"
          title={item.pinned ? '고정 해제' : '고정하기'}
          onClick={() => onTogglePin(item.itemId, !item.pinned)}
          disabled={editing}
        >
          {item.pinned ? '📌' : '📍'}
        </button>
        <button className="icon-btn" title="AI 도슨트 듣기" onClick={() => onOpenDocent(item)} disabled={editing}>🎧</button>
        <button className="icon-btn danger" title="삭제" onClick={() => onDelete(item.itemId)} disabled={editing}>🗑️</button>
      </div>
    </div>
  );
}
