import { useEffect, useState } from 'react';
import { itemStatusLevel, STATUS_LABEL, isIndoorPlace } from '../utils/statusLevel';
import { canOpenInKakaoMap, openInKakaoMap } from '../utils/kakaoMap';
import { openExternalLink } from '../utils/externalLink';
import { recordView } from '../utils/viewHistory';
import { sanitizeApiText } from '../utils/sanitizeApiText';
import { introLine } from '../utils/introLine';
import VisitTimePicker, { normalizeTime } from './VisitTimePicker';
import PlaceThumb from './PlaceThumb';
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
  /** weatherAlerted가 비/폭염 중 어느 쪽인지 - 배지 라벨을 "우천"/"폭염"으로 나누기 위함 */
  rainTrigger = false,
  heatTrigger = false,
  /** @deprecated */
  alerted = false,
  /** 알림을 눌러 들어온 경우 잠깐 스크롤+펄스로 눈에 띄게 한다(App.jsx의 deep-link 처리 참고) */
  highlighted = false,
  completed = false,
  onUpdateTime,
  onUpdateItem,
  onDelete,
  onToggleComplete,
  onOpenDocent,
  onOpenHistory,
}) {
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [draft, setDraft] = useState(() => draftFromItem(item));
  const isWeather = !completed && (weatherAlerted || alerted) && !isIndoorPlace(item);
  const businessAlerted = !completed && (closedDayAlerted || hoursEndedAlerted);
  const crowdAlert = !completed && crowdAlerted;
  // 완료(지난 일정) 항목은 상태 컬러·경고를 걷어내고 회색조로 둔다.
  const status = completed ? 'NORMAL' : itemStatusLevel(item, {
    weatherAlerted: isWeather,
    businessAlerted,
    crowdAlerted: crowdAlert,
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
  // 카드 이름 옆에 붙일 한 줄 소개 - 상세에서 따로 보여주는 item.overview 전문과 별개로,
  // 접었을 때도 바로 보이도록 첫 문장만 짧게 뽑는다(2026-09-12 사용자 요청).
  const intro = !editing ? introLine(item.overview) : '';

  function handleOpenMap() {
    openInKakaoMap(item);
  }

  /**
   * 배지 라벨 - 실제 발생한 트리거를 우선순위 없이 전부 병기한다("혼잡 · 폭염" 형태,
   * 2026-09-10 핸드오프 브리프: 고정 "주의" 텍스트만으로는 원인을 알 수 없던 문제).
   * 최대 3개까지만(그 이상은 CSS 말줄임), "·"로 구분. 트리거가 하나도 없으면(예: 서버
   * crowdAlerted는 false인데 itemStatusLevel 자체 휴리스틱으로만 WARNING이 된 경우) 기존처럼
   * 상태값 라벨(정상/주의/긴급)로 폴백한다.
   */
  const MAX_BADGE_LABELS = 3;
  function summaryStatusLabel() {
    if (completed) return '다녀옴';
    const labels = [];
    if (businessAlerted && closedDayAlerted) labels.push('휴무');
    if (businessAlerted && hoursEndedAlerted) labels.push('마감');
    if (crowdAlert) labels.push('혼잡');
    if (isWeather && rainTrigger) labels.push('우천');
    if (isWeather && heatTrigger) labels.push('폭염');
    if (labels.length > 0) return labels.slice(0, MAX_BADGE_LABELS).join(' · ');
    return STATUS_LABEL[status];
  }

  return (
    <div
      id={`item-${item.itemId}`}
      className={`item-card ${statusClass} ${item.pinned ? 'pinned' : ''} ${isWeather ? 'weather-affected' : ''} ${businessAlerted ? 'business-affected' : ''} ${completed ? 'is-completed' : ''} ${editing ? 'editing' : ''} ${showDetail ? 'is-expanded' : 'is-collapsed'} ${highlighted ? 'item-card-highlighted' : ''}`}
      data-status={status}
    >
      <span className={`item-status-rail ${statusClass}`} title={STATUS_LABEL[status]} aria-hidden="true" />

      {onToggleComplete && (
        <button
          type="button"
          className={`item-complete-toggle ${completed ? 'is-done' : ''}`}
          onClick={() => onToggleComplete(item.itemId, !completed)}
          aria-label={completed ? '완료됨' : '다녀왔어요 표시하기'}
          title={completed ? '다시 진행 중으로' : '다녀온 곳으로 표시'}
        >
          {/* 미완료일 땐 hover/press로만 살짝 보이는 "고스트 체크" - 탭하면 완료 처리된다는 걸
              암시한다(2026-09-11 스펙: 아이콘이 인터랙션 가능해 보이지 않던 문제 개선). */}
          <span className="item-complete-check" aria-hidden="true">✓</span>
        </button>
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

      <PlaceThumb item={item} className="item-thumb-sm" />

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
        <span className="item-name-wrap">
          <span className="item-name">{item.placeName}</span>
          {intro && <span className="item-intro">· {intro}</span>}
        </span>
        <span className={`item-status-chip ${statusClass}`}>{summaryStatusLabel()}</span>
        {!editing && (
          <span className="item-card-chevron" aria-hidden="true">{expanded ? '▾' : '▸'}</span>
        )}
      </button>

      {showDetail && (
      <div className="item-detail">
      <div className="item-body">
        {!editing ? (
          <>
            {item.isAlternate && (
              onOpenHistory ? (
                <button type="button" className="item-changed-badge" onClick={onOpenHistory}>
                  🟡 변경됨 · 이력 보기
                </button>
              ) : (
                <span className="alt-badge">추천으로 담은 장소</span>
              )
            )}

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
              {item.restDateText && (
                <div className="reco-info-row reco-restdate">정기휴무: {sanitizeApiText(item.restDateText)}</div>
              )}
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

            {!completed && item.crowdRate !== null && item.crowdRate !== undefined && (
              <div className={`item-crowd ${statusClass}`}>혼잡도 {Math.round(item.crowdRate)}%</div>
            )}

            <div className="item-text-actions">
              {onToggleComplete && (
                <button
                  type="button"
                  className="item-text-btn"
                  onClick={() => onToggleComplete(item.itemId, !completed)}
                >
                  <span className="item-text-btn-icon" aria-hidden="true">{completed ? '↩️' : '✓'}</span>
                  {completed ? '되돌리기' : '다녀옴'}
                </button>
              )}
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
