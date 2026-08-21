import { useEffect, useState } from 'react';
import { itemStatusLevel, STATUS_LABEL, isIndoorPlace } from '../utils/statusLevel';
import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';
import { openExternalLink } from '../utils/externalLink';
import { recordView } from '../utils/viewHistory';
import VisitTimePicker, { normalizeTime } from './VisitTimePicker';
import TagGroupPicker from './TagGroupPicker';

function draftFromItem(src) {
  return {
    placeName: src.placeName || '',
    scheduledTime: src.scheduledTime || '',
    tags: [...(src.tags || [])],
    addr1: src.addr1 || '',
    tel: src.tel || '',
    useFeeText: src.useFeeText || '',
    isFree: Boolean(src.isFree),
    estimatedCostPerPerson: src.estimatedCostPerPerson === null || src.estimatedCostPerPerson === undefined
      ? ''
      : String(src.estimatedCostPerPerson),
    restDateText: src.restDateText || '',
  };
}

/**
 * 일정 카드 - 정상·주의·긴급 상태 컬러로 시간 뱃지·테두리를 맞춘다.
 */
export default function ItineraryItemCard({
  item,
  weatherAlerted = false,
  closedDayAlerted = false,
  hoursEndedAlerted = false,
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
  const businessAlerted = closedDayAlerted || hoursEndedAlerted;
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
        estimatedCostPerPerson: draft.isFree
          ? 0
          : (draft.estimatedCostPerPerson.trim() === '' ? null : Number(draft.estimatedCostPerPerson)),
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

  const mapAvailable = canOpenInKakaoMap(item);

  function handleOpenMap() {
    openInKakaoMap(item);
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
        <VisitTimePicker
          className={statusClass}
          value={item.scheduledTime || ''}
          onChange={(next) => {
            if (next) onUpdateTime?.(item.itemId, next);
          }}
          aria-label={`${item.placeName || '장소'} 방문 시각`}
        />
      ) : (
        <VisitTimePicker
          className={statusClass}
          value={draft.scheduledTime}
          onChange={(next) => setDraft((prev) => ({ ...prev, scheduledTime: next }))}
          aria-label="방문 시각 수정"
        />
      )}

      <div className="item-body">
        {!editing ? (
          <>
            <div className="item-head">
              {mapAvailable ? (
                <button
                  type="button"
                  className="item-name item-name-map"
                  onClick={handleOpenMap}
                  title="카카오맵에서 보기"
                >
                  {item.placeName}
                </button>
              ) : (
                <span className="item-name">{item.placeName}</span>
              )}
              <span className={`item-status-chip ${statusClass}`}>{STATUS_LABEL[status]}</span>
              {item.isAlternate && <span className="alt-badge" title="추천으로 담은 장소">추천</span>}
              {isWeather && <span className="weather-affected-badge" title="비·폭염 영향 야외 일정">⚠️ 야외</span>}
              {closedDayAlerted && <span className="business-affected-badge" title="방문일이 정기휴무 요일">🚫 휴무</span>}
              {hoursEndedAlerted && !closedDayAlerted && (
                <span className="business-affected-badge" title="지금 영업시간이 끝났어요">🕐 영업종료</span>
              )}
              {crowdAlerted && !isWeather && !businessAlerted && (
                <span className="crowd-affected-badge" title="혼잡 주의">👥 혼잡</span>
              )}
              {item.pinned && (
                <span
                  className="pin-badge"
                  title={item.pinnedReason || '고정됨 - 새 장소 추천에서 이 장소 근처를 우선 보여드려요'}
                >
                  📌
                </span>
              )}
            </div>

            {item.tags?.length > 0 && (
              <div className="item-tags">
                {item.tags.map((t) => <span key={t} className="tag-chip">{t}</span>)}
              </div>
            )}

            <div className="reco-info">
              {item.addr1 && (
                mapAvailable ? (
                  <button type="button" className="reco-info-row reco-info-link" onClick={handleOpenMap}>
                    📍 {item.addr1}
                  </button>
                ) : (
                  <div className="reco-info-row">📍 {item.addr1}</div>
                )
              )}
              {(item.isFree || item.useFeeText) && (
                <div className="reco-info-row">🎫 {item.isFree ? '무료' : item.useFeeText}</div>
              )}
              {item.estimatedCostPerPerson === null || item.estimatedCostPerPerson === undefined ? (
                <div className="reco-info-row reco-cost-unknown">💰 정보없음</div>
              ) : (
                <div className="reco-info-row">
                  💰 {item.estimatedCostPerPerson === 0 ? '무료' : `1인 ${item.estimatedCostPerPerson.toLocaleString()}원`}
                </div>
              )}
              {item.tel && (
                <a className="reco-info-row reco-info-link" href={`tel:${item.tel}`}>☎️ {item.tel}</a>
              )}
              {item.strollerFriendly === true && <div className="reco-info-row">🍼 유모차 이용 가능</div>}
              {item.accessibleFriendly && <div className="reco-info-row">♿ 무장애 시설</div>}
              {item.restDateText && <div className="reco-info-row reco-restdate">🚫 정기휴무: {item.restDateText}</div>}
              {item.homepageUrl && (
                <button
                  type="button"
                  className="reco-info-row reco-info-link"
                  onClick={() => {
                    recordView({ type: 'place', id: item.contentId, name: item.placeName, thumbnail: item.thumbnailUrl });
                    openExternalLink(item.homepageUrl);
                  }}
                >
                  🔗 홈페이지
                </button>
              )}
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
              <TagGroupPicker className="item-edit-tags" selected={draft.tags} onToggle={toggleTag} />
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
              <>
                <label className="item-edit-label">
                  이용요금
                  <input
                    type="text"
                    value={draft.useFeeText}
                    onChange={(e) => setDraft((prev) => ({ ...prev, useFeeText: e.target.value }))}
                    placeholder="예: 성인 3,000원"
                  />
                </label>
                <label className="item-edit-label">
                  1인 예상 비용(원)
                  <input
                    type="number"
                    min={0}
                    value={draft.estimatedCostPerPerson}
                    onChange={(e) => setDraft((prev) => ({ ...prev, estimatedCostPerPerson: e.target.value }))}
                    placeholder="모르면 비워두세요"
                  />
                </label>
              </>
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
          title={
            item.pinned
              ? '고정 해제 - 지금은 이 장소 근처로 추천 중이에요'
              : '고정하기 - 고정하면 아래 새로운 장소 추천에서 이 장소 근처를 우선 보여드려요'
          }
          onClick={() => onTogglePin(item.itemId, !item.pinned)}
          disabled={editing}
        >
          {item.pinned ? '📌' : '📍'}
        </button>
        <button
          className="icon-btn"
          title="카카오맵에서 보기"
          onClick={handleOpenMap}
          disabled={editing || !mapAvailable}
        >
          🗺️
        </button>
        <button className="icon-btn" title="AI 도슨트 듣기" onClick={() => onOpenDocent(item)} disabled={editing}>🎧</button>
        <button
          className="icon-btn danger"
          title={item.backupPlaceName ? `삭제 (자동으로 "${item.backupPlaceName}"로 교체돼요)` : '삭제'}
          onClick={() => onDelete(item.itemId)}
          disabled={editing}
        >🗑️</button>
      </div>
    </div>
  );
}
