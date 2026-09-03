import { useEffect, useState } from 'react';
import { itemStatusLevel, STATUS_LABEL, isIndoorPlace } from '../utils/statusLevel';
import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';
import { openExternalLink } from '../utils/externalLink';
import { recordView } from '../utils/viewHistory';
import VisitTimePicker, { normalizeTime } from './VisitTimePicker';
import TagGroupPicker from './TagGroupPicker';
import PlaceOverview from './PlaceOverview';
import PlaceDetailFacts from './PlaceDetailFacts';
import SituationalChips from './SituationalChips';

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
    indoorYn: src.indoor ?? null,
    rainSensitivity: src.rainSensitivity || '',
    congestionSensitivity: src.congestionSensitivity || '',
  };
}

function situationalChanged(draft, item) {
  const origIndoor = item.indoor ?? null;
  const origRain = item.rainSensitivity || '';
  const origCrowd = item.congestionSensitivity || '';
  return draft.indoorYn !== origIndoor
    || (draft.rainSensitivity || '') !== origRain
    || (draft.congestionSensitivity || '') !== origCrowd;
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
  const [expanded, setExpanded] = useState(false);
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
        restDateText: draft.restDateText.trim(),
        ...(situationalChanged(draft, item) ? {
          indoorYn: draft.indoorYn,
          rainSensitivity: draft.rainSensitivity || null,
          congestionSensitivity: draft.congestionSensitivity || null,
        } : {}),
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
  const showDetail = expanded || editing;

  function handleOpenMap() {
    openInKakaoMap(item);
  }

  function summaryStatusLabel() {
    if (closedDayAlerted) return '휴무';
    if (hoursEndedAlerted) return '영업종료';
    if (isWeather) return '야외';
    if (crowdAlerted) return '혼잡';
    return STATUS_LABEL[status];
  }

  return (
    <div
      className={`item-card ${statusClass} ${item.pinned ? 'pinned' : ''} ${isWeather ? 'weather-affected' : ''} ${businessAlerted ? 'business-affected' : ''} ${editing ? 'editing' : ''} ${showDetail ? 'is-expanded' : 'is-collapsed'}`}
      data-status={status}
    >
      <span className={`item-status-rail ${statusClass}`} title={STATUS_LABEL[status]} aria-hidden="true" />

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

      <button
        type="button"
        className="item-card-summary"
        onClick={() => {
          if (editing) return;
          setExpanded((open) => !open);
        }}
        disabled={editing}
        aria-expanded={showDetail}
      >
        <span className="item-name">{item.placeName}</span>
        <span className={`item-status-chip ${statusClass}`}>{summaryStatusLabel()}</span>
        {!editing && (
          <span className="item-card-chevron" aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        )}
      </button>

      {showDetail && (
      <div className="item-detail">
      {item.thumbnailUrl ? (
        <img className="item-thumb" src={item.thumbnailUrl} alt="" loading="lazy" />
      ) : null}

      <div className="item-body">
        {!editing ? (
          <>
            {item.isAlternate && <span className="alt-badge">추천으로 담은 장소</span>}

            {item.tags?.length > 0 && (
              <div className="item-tags">
                {item.tags.map((t) => <span key={t} className="tag-chip">{t}</span>)}
              </div>
            )}

            <SituationalChips
              indoor={item.indoor}
              rainSensitivity={item.rainSensitivity}
              congestionSensitivity={item.congestionSensitivity}
              inferredSource={item.inferredSource}
            />

            <PlaceOverview text={item.overview} />

            <div className="reco-info">
              {item.addr1 && (
                mapAvailable ? (
                  <button type="button" className="reco-info-row reco-info-link" onClick={handleOpenMap}>
                    {item.addr1}
                  </button>
                ) : (
                  <div className="reco-info-row">{item.addr1}</div>
                )
              )}
              {(item.isFree || item.useFeeText) && (
                <div className="reco-info-row">{item.isFree ? '무료' : item.useFeeText}</div>
              )}
              {item.tel && (
                <a className="reco-info-row reco-info-link" href={`tel:${item.tel}`}>{item.tel}</a>
              )}
              {item.strollerFriendly === true && <div className="reco-info-row">유모차 이용 가능</div>}
              {item.accessibleFriendly && <div className="reco-info-row">무장애 시설</div>}
              {item.restDateText && <div className="reco-info-row reco-restdate">정기휴무: {item.restDateText}</div>}
              {item.homepageUrl && (
                <button
                  type="button"
                  className="reco-info-row reco-info-link"
                  onClick={() => {
                    recordView({ type: 'place', id: item.contentId, name: item.placeName, thumbnail: item.thumbnailUrl });
                    openExternalLink(item.homepageUrl);
                  }}
                >
                  홈페이지
                </button>
              )}
              <PlaceDetailFacts facts={item.detailFacts} />
            </div>

            {item.crowdRate !== null && item.crowdRate !== undefined && (
              <div className={`item-crowd ${statusClass}`}>혼잡도 {Math.round(item.crowdRate)}%</div>
            )}

            {item.pinned && (
              <p className="item-pin-hint">검색할 때 근처 기준의 기본값이에요. 검색 화면에서 바꿀 수 있어요.</p>
            )}

            <div className="item-text-actions">
              <button
                type="button"
                className="item-text-btn"
                onClick={handleOpenMap}
                disabled={!mapAvailable}
              >
                <span className="item-text-btn-icon" aria-hidden="true">🗺️</span>
                지도
              </button>
              <button
                type="button"
                className="item-text-btn"
                onClick={() => setEditing(true)}
              >
                <span className="item-text-btn-icon" aria-hidden="true">✏️</span>
                수정
              </button>
              <button
                type="button"
                className="item-text-btn"
                onClick={() => onTogglePin(item.itemId, !item.pinned)}
              >
                <span className="item-text-btn-icon" aria-hidden="true">📌</span>
                {item.pinned ? '고정 해제' : '고정'}
              </button>
              <button
                type="button"
                className="item-text-btn"
                onClick={() => onOpenDocent(item)}
              >
                <span className="item-text-btn-icon" aria-hidden="true">🎧</span>
                도슨트
              </button>
              <button
                type="button"
                className="item-text-btn danger"
                onClick={() => onDelete(item.itemId)}
              >
                <span className="item-text-btn-icon" aria-hidden="true">🗑️</span>
                삭제
              </button>
            </div>
            {!item.pinned && (
              <p className="item-pin-hint">고정하면 검색의 근처 기준이 이 장소로 바뀌어요. 검색에서 직접 골라도 돼요.</p>
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

            <p className="item-edit-hint">상황 태그는 자동으로 채워져요. 틀린 경우만 바꿔 주세요.</p>
            <label className="item-edit-label">
              실내/실외
              <select
                value={draft.indoorYn === true ? 'true' : draft.indoorYn === false ? 'false' : ''}
                onChange={(e) => setDraft((prev) => ({
                  ...prev,
                  indoorYn: e.target.value === '' ? null : e.target.value === 'true',
                }))}
              >
                <option value="">자동</option>
                <option value="true">실내</option>
                <option value="false">실외</option>
              </select>
            </label>
            <label className="item-edit-label">
              우천 민감도
              <select
                value={draft.rainSensitivity}
                onChange={(e) => setDraft((prev) => ({ ...prev, rainSensitivity: e.target.value }))}
              >
                <option value="">자동</option>
                <option value="SENSITIVE">우천 민감</option>
                <option value="INSENSITIVE">우천 둔감</option>
              </select>
            </label>
            <label className="item-edit-label">
              혼잡 민감도
              <select
                value={draft.congestionSensitivity}
                onChange={(e) => setDraft((prev) => ({ ...prev, congestionSensitivity: e.target.value }))}
              >
                <option value="">자동</option>
                <option value="SENSITIVE">혼잡 민감</option>
                <option value="INSENSITIVE">혼잡 둔감</option>
              </select>
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
      </div>
      )}
    </div>
  );
}
