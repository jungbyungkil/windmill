import { useState, useEffect, useMemo } from 'react';
import PinwheelHero from './PinwheelHero';
import PinwheelLoader from './PinwheelLoader';
import TripStoryFeed from './TripStoryFeed';
import CoachTour from './CoachTour';
import RecommendationCard from './RecommendationCard';
import NudgeCard, { loadSituationByGeolocation, maybeNotifySituation } from './NudgeCard';
import * as api from '../api/windmillApi';
import {
  COMPANION_TYPE_OPTIONS,
  AGE_GROUP_OPTIONS,
  CHILD_AGE_OPTIONS,
  FIXED_PARTY_SIZE_BY_COMPANION_TYPE,
  EXTENDED_FAMILY_MIN_SIZE,
  EXTENDED_FAMILY_MAX_SIZE,
} from '../constants';

const COMPANION_LABEL = Object.fromEntries(COMPANION_TYPE_OPTIONS.map((o) => [o.value, o.label]));

/** 카카오 검색 결과는 contentId가 없어서(관광공사 매칭 전) 좌표+이름으로 목록 key/식별을 대신한다 */
function anchorCandidateKey(candidate) {
  return candidate.contentId || `${candidate.placeName}_${candidate.mapX}_${candidate.mapY}`;
}

function todayIso() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function formatDraftDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][d.getDay()];
  return `${d.getMonth() + 1}/${d.getDate()} (${weekday})`;
}

export default function CreateTripScreen({
  sessionId,
  onCreate,
  onStartFromStory,
  loading,
  loadingStage,
  startingStoryId,
  error,
  draftItineraryId,
  onResumeDraft,
}) {
  const [regions, setRegions] = useState([]);
  const [regionsError, setRegionsError] = useState(null);
  const [sidoCode, setSidoCode] = useState('');
  const [signguFullCode, setSignguFullCode] = useState('');
  const [tripDate, setTripDate] = useState(todayIso());
  const [dateTouched, setDateTouched] = useState(false);
  const [companionType, setCompanionType] = useState('SOLO');
  const [partySize, setPartySize] = useState(1);
  const [partySizeTouched, setPartySizeTouched] = useState(false);
  const [adultAgeGroup, setAdultAgeGroup] = useState('THIRTIES');
  const [childAges, setChildAges] = useState([]);
  const [withPet, setWithPet] = useState(false);
  const [strollerFriendly, setStrollerFriendly] = useState(false);
  const [accessibleFriendly, setAccessibleFriendly] = useState(false);
  const [situation, setSituation] = useState(null);
  const [situationLoading, setSituationLoading] = useState(true);
  const [situationDismissed, setSituationDismissed] = useState(false);
  const [ongoingTrips, setOngoingTrips] = useState([]);
  const [ongoingLoading, setOngoingLoading] = useState(Boolean(sessionId));
  const [deletingDraftId, setDeletingDraftId] = useState(null);
  // 고정 일정(앵커) 등록 - 이미 계획(예: DDP 19:00 공연)이 있는 사용자를 위한 사전 등록
  const [anchorQuery, setAnchorQuery] = useState('');
  const [anchorResults, setAnchorResults] = useState(null);
  const [anchorSearchLoading, setAnchorSearchLoading] = useState(false);
  const [anchorCandidate, setAnchorCandidate] = useState(null);
  const [anchorTime, setAnchorTime] = useState('19:00');
  const [anchorResolvingKey, setAnchorResolvingKey] = useState(null);
  const [anchorResolveError, setAnchorResolveError] = useState(null);

  useEffect(() => {
    api.getRegions()
      .then((list) => {
        setRegions(list);
        if (list.length > 0) setSidoCode(list[0].sidoCode);
      })
      .catch((e) => setRegionsError(e.message));
  }, []);

  // 미완료 당일치기 목록 (날짜별 줄 + 이어하기)
  useEffect(() => {
    if (!sessionId) {
      setOngoingTrips([]);
      setOngoingLoading(false);
      return;
    }
    let cancelled = false;
    setOngoingLoading(true);
    api.getOngoingItineraries(sessionId)
      .then((list) => {
        if (!cancelled) setOngoingTrips(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        if (!cancelled) setOngoingTrips([]);
      })
      .finally(() => {
        if (!cancelled) setOngoingLoading(false);
      });
    return () => { cancelled = true; };
  }, [sessionId, draftItineraryId]);

  // 앱 실행 시 현재 위치 기반 상황 요약 + (주의 시) Notification
  useEffect(() => {
    let cancelled = false;
    setSituationLoading(true);
    loadSituationByGeolocation((lat, lon) => api.getSituationByLocation(lat, lon))
      .then((res) => {
        if (cancelled) return;
        setSituation(res);
        if (res) maybeNotifySituation(res);
      })
      .catch(() => {
        if (!cancelled) setSituation(null);
      })
      .finally(() => {
        if (!cancelled) setSituationLoading(false);
      });
    return () => { cancelled = true; };
  }, []);

  const selectedSido = regions.find((r) => r.sidoCode === sidoCode);
  const signguOptions = selectedSido?.signgus || [];

  useEffect(() => {
    if (signguOptions.length > 0 && !signguOptions.some((s) => s.signguFullCode === signguFullCode)) {
      setSignguFullCode(signguOptions[0].signguFullCode);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidoCode, regions]);

  const regionLabel = useMemo(() => {
    if (!selectedSido || !signguFullCode) return '';
    const signgu = signguOptions.find((s) => s.signguFullCode === signguFullCode);
    if (!signgu) return selectedSido.sidoName || '';
    return `${selectedSido.sidoName} ${signgu.signguName}`;
  }, [selectedSido, signguOptions, signguFullCode]);

  const today = todayIso();
  const dateBeforeToday = Boolean(tripDate && tripDate < today);
  const dateInvalid = !tripDate || dateBeforeToday;

  // 대가족 여행만 총 인원수를 직접 입력(5~9명), 나머지는 동반유형에 고정값을 세팅하고 입력을 잠근다
  const isExtendedFamily = companionType === 'EXTENDED_FAMILY';
  const partySizeError = isExtendedFamily
    && (partySize < EXTENDED_FAMILY_MIN_SIZE || partySize > EXTENDED_FAMILY_MAX_SIZE)
    ? `대가족 여행은 ${EXTENDED_FAMILY_MIN_SIZE}명 이상 ${EXTENDED_FAMILY_MAX_SIZE}명 이하만 가능해요`
    : null;
  // 성인 최소 1명 보장을 위해 자녀 수는 총 인원수-1을 넘을 수 없음(내부 계산값으로만 사용, 화면 미표기)
  const maxChildren = Math.max(0, (partySize || 0) - 1);
  const canAddChild = childAges.length < maxChildren;

  // 동반유형/총 인원수가 줄어들어 기존 자녀 목록이 상한을 넘으면 초과분을 잘라낸다
  useEffect(() => {
    setChildAges((prev) => (prev.length > maxChildren ? prev.slice(0, maxChildren) : prev));
  }, [maxChildren]);

  const canSubmit = Boolean(signguFullCode && tripDate && !dateBeforeToday && !partySizeError);

  function handleCompanionTypeChange(value) {
    setCompanionType(value);
    const fixedSize = FIXED_PARTY_SIZE_BY_COMPANION_TYPE[value];
    if (fixedSize != null) {
      setPartySize(fixedSize);
    } else if (partySize < EXTENDED_FAMILY_MIN_SIZE || partySize > EXTENDED_FAMILY_MAX_SIZE) {
      setPartySize(EXTENDED_FAMILY_MIN_SIZE);
    }
    setPartySizeTouched(false);
  }

  function handleDateChange(value) {
    setDateTouched(true);
    if (!value) {
      setTripDate('');
      return;
    }
    // 당일치기: 하루 날짜만 허용 (과거면 오늘로 보정)
    setTripDate(value < today ? today : value);
  }

  function tripFields() {
    return {
      signguFullCode,
      startDate: tripDate,
      endDate: tripDate,
      companionType,
      partySize,
      withPet,
      strollerFriendly,
      accessibleFriendly,
      adultAgeGroup,
      childAges,
    };
  }

  function handleSubmit(e) {
    e.preventDefault();
    handleSmartStart();
  }

  function handleSmartStart() {
    setDateTouched(true);
    setPartySizeTouched(true);
    if (!canSubmit) return;
    onCreate(tripFields());
  }

  function handleAnchorStart() {
    setDateTouched(true);
    setPartySizeTouched(true);
    if (!canSubmit || !anchorCandidate) return;
    onCreate({
      ...tripFields(),
      anchor: anchorCandidate,
      anchorTime,
    });
  }

  async function handleAnchorSearch() {
    if (!anchorQuery.trim() || !signguFullCode) return;
    setAnchorSearchLoading(true);
    setAnchorResolveError(null);
    try {
      const results = await api.searchPlacesByName({ regionCode: signguFullCode, query: anchorQuery.trim() });
      setAnchorResults(results);
    } catch {
      setAnchorResults([]);
    } finally {
      setAnchorSearchLoading(false);
    }
  }

  /** 카카오 검색 결과(contentId 없음)에서 고른 후보를 그 자리에서 관광공사 데이터로 매칭한다 */
  async function handleSelectAnchor(candidate) {
    const key = anchorCandidateKey(candidate);
    setAnchorResolveError(null);
    setAnchorResolvingKey(key);
    try {
      const resolved = await api.resolvePlaceByName({
        regionCode: signguFullCode,
        placeName: candidate.placeName,
        mapX: candidate.mapX,
        mapY: candidate.mapY,
      });
      if (!resolved || resolved.length === 0) {
        setAnchorResolveError(`'${candidate.placeName}'은(는) 관광공사 데이터에 없어 등록할 수 없어요. 다른 이름으로 찾아보세요.`);
        return;
      }
      setAnchorCandidate(resolved[0]);
      setAnchorResults(null);
      setAnchorQuery('');
    } catch {
      setAnchorResolveError('장소 정보를 불러오지 못했어요. 다시 시도해 주세요.');
    } finally {
      setAnchorResolvingKey(null);
    }
  }

  function handleClearAnchor() {
    setAnchorCandidate(null);
    setAnchorResolveError(null);
  }

  function handleAddChild() {
    setChildAges((prev) => (prev.length >= maxChildren ? prev : [...prev, 10]));
  }

  function handleChangeChildAge(index, age) {
    setChildAges((prev) => prev.map((a, i) => (i === index ? age : a)));
  }

  function handleRemoveChild(index) {
    setChildAges((prev) => prev.filter((_, i) => i !== index));
  }

  async function handleDeleteDraft(trip) {
    const label = trip.regionDisplayName
      ? `${formatDraftDate(trip.startDate)} ${trip.regionDisplayName}`
      : '이 일정';
    if (!window.confirm(`${label}을(를) 삭제할까요? 되돌릴 수 없어요.`)) return;
    setDeletingDraftId(trip.itineraryId);
    try {
      await api.deleteItinerary(trip.itineraryId);
      setOngoingTrips((prev) => prev.filter((t) => t.itineraryId !== trip.itineraryId));
    } catch (e) {
      alert(`삭제 실패: ${e.message}`);
    } finally {
      setDeletingDraftId(null);
    }
  }

  function handleStartFromStory(story) {
    setDateTouched(true);
    if (dateInvalid || !onStartFromStory) return;
    onStartFromStory(story, tripDate);
  }

  return (
    <div className="create-trip-screen">
      {loading && (
        <PinwheelLoader
          message={
            loadingStage
              || (anchorCandidate
                ? `${anchorCandidate.placeName} 기준으로 장소를 찾고 있어요...`
                : '식당 2 · 카페 1 · 일정 4곳을 찾고 있어요...')
          }
        />
      )}
      <PinwheelHero />
      <h1 className="brand-title">바람따라</h1>
      <p className="brand-tagline">당일치기 여행의 날씨·혼잡·동선 변수를 미리 알려주고, 대안을 쌓아 모두가 참고하는 가이드</p>

      <form id="trip-form" className="trip-form" onSubmit={handleSubmit}>
        <div className="trip-form-row" data-coach="region">
          <label className="trip-form-label">여행 지역</label>
          {regionsError && <div className="error-msg">❌ 지역 목록을 불러오지 못했어요: {regionsError}</div>}
          <div className="trip-form-region-selects">
            <select value={sidoCode} onChange={(e) => setSidoCode(e.target.value)} disabled={regions.length === 0}>
              {regions.map((r) => (
                <option key={r.sidoCode} value={r.sidoCode}>{r.sidoName}</option>
              ))}
            </select>
            <select value={signguFullCode} onChange={(e) => setSignguFullCode(e.target.value)} disabled={signguOptions.length === 0}>
              {signguOptions.map((s) => (
                <option key={s.signguFullCode} value={s.signguFullCode}>{s.signguName}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="trip-form-row">
          <label className="trip-form-label" htmlFor="trip-date">
            여행 날짜 <span className="trip-form-hint-inline">하루만 선택</span>
          </label>
          <p className="trip-form-date-help">당일치기만 지원해요. 추천 기록으로 시작할 때도 이 날짜가 적용돼요.</p>
          <input
            id="trip-date"
            type="date"
            className={`trip-form-date-single ${dateTouched && dateInvalid ? 'invalid' : ''}`}
            value={tripDate}
            min={today}
            onChange={(e) => handleDateChange(e.target.value)}
            onBlur={() => setDateTouched(true)}
            required
            aria-invalid={dateTouched && dateInvalid}
            aria-describedby="trip-date-help"
          />
          <span id="trip-date-help" className="sr-only">하루 날짜만 선택하세요</span>
          {dateTouched && !tripDate && (
            <div className="error-msg">❌ 여행 날짜를 선택해 주세요</div>
          )}
          {dateBeforeToday && (
            <div className="error-msg">❌ 여행일은 오늘 이후여야 해요</div>
          )}
        </div>

        <div className="trip-form-row">
          <label className="trip-form-label">누구와 함께하나요?</label>
          <div className="reco-tag-row">
            {COMPANION_TYPE_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                className={`tag ${companionType === opt.value ? 'selected' : ''}`}
                onClick={() => handleCompanionTypeChange(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <label className="trip-form-party-size">
            총 인원수
            <input
              type="number"
              min={isExtendedFamily ? EXTENDED_FAMILY_MIN_SIZE : partySize}
              max={isExtendedFamily ? EXTENDED_FAMILY_MAX_SIZE : partySize}
              value={partySize}
              readOnly={!isExtendedFamily}
              disabled={!isExtendedFamily}
              aria-invalid={partySizeTouched && Boolean(partySizeError)}
              onChange={(e) => {
                if (!isExtendedFamily) return;
                const n = parseInt(e.target.value, 10);
                setPartySize(Number.isNaN(n) ? '' : n);
              }}
              onBlur={() => setPartySizeTouched(true)}
            />
            <span className="trip-form-hint-inline">명 - 슬롯별/전체 예상 비용 계산에 쓰여요</span>
          </label>
          {isExtendedFamily && partySizeTouched && partySizeError && (
            <div className="error-msg">❌ {partySizeError}</div>
          )}
          <label className="trip-form-checkbox">
            <input type="checkbox" checked={withPet} onChange={(e) => setWithPet(e.target.checked)} />
            🐾 반려동물과 함께해요
          </label>
          <label className="trip-form-checkbox">
            <input
              type="checkbox"
              checked={strollerFriendly}
              onChange={(e) => setStrollerFriendly(e.target.checked)}
            />
            🍼 유모차 동반 (유모차 이용 가능한 곳 우선 추천)
          </label>
          <label className="trip-form-checkbox">
            <input
              type="checkbox"
              checked={accessibleFriendly}
              onChange={(e) => setAccessibleFriendly(e.target.checked)}
            />
            ♿ 무장애 이동 (장애인 동반 - 무장애 시설 우선 추천)
          </label>
        </div>

        <div className="trip-form-row">
          <label className="trip-form-label">성인 연령대</label>
          <div className="reco-tag-row">
            {AGE_GROUP_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                className={`tag ${adultAgeGroup === opt.value ? 'selected' : ''}`}
                onClick={() => setAdultAgeGroup(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>

          <div className="trip-form-child-ages">
            <div className="trip-form-child-ages-head">
              <span className="trip-form-child-ages-label">동반 자녀 나이 (선택)</span>
              <button
                type="button"
                className="btn-child-add"
                onClick={handleAddChild}
                disabled={!canAddChild}
                title={!canAddChild ? '성인 최소 1명을 위해 더 이상 자녀를 추가할 수 없어요' : undefined}
              >
                + 자녀 추가
              </button>
            </div>
            {childAges.map((age, index) => (
              <div key={index} className="trip-form-child-row">
                <span className="trip-form-child-index">자녀 {index + 1}</span>
                <select
                  value={age}
                  onChange={(e) => handleChangeChildAge(index, Number(e.target.value))}
                >
                  {CHILD_AGE_OPTIONS.map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
                <button
                  type="button"
                  className="icon-btn danger"
                  aria-label="자녀 삭제"
                  onClick={() => handleRemoveChild(index)}
                >
                  🗑️
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="plan-mode-section" data-coach="modes">
          <h2 className="plan-mode-heading">일정은 이렇게 짜요</h2>
          <p className="plan-mode-lead">세 가지 중 하나만 고르면 바로 시작해요.</p>

          <article className="plan-mode-card" data-coach="mode-smart">
            <span className="plan-mode-badge">1</span>
            <h3 className="plan-mode-title">스마트 동선 자동</h3>
            <p className="plan-mode-desc">
              지금 시각과 상관없이 <strong>식당 2곳 · 카페 1곳 · 일정 4곳</strong>을 채워 드려요.
              제일 인기 있는 곳을 먼저 담고, 붐비는 명소는 비교적 한산한 오전에 배치해요.
            </p>
            <button className="btn-primary btn-start" type="button" onClick={handleSmartStart} disabled={loading || !canSubmit}>
              {loading ? '일정 준비 중...' : '🌬️ 스마트 동선으로 시작'}
            </button>
          </article>

          <article className="plan-mode-card" data-coach="mode-story">
            <span className="plan-mode-badge">2</span>
            <h3 className="plan-mode-title">다른 여행자 일정 참고</h3>
            <p className="plan-mode-desc">
              다녀온 사람이 남긴 당일치기를 그대로 복제해 시작할 수 있어요.
            </p>
            <TripStoryFeed
              signguFullCode={signguFullCode}
              regionLabel={regionLabel}
              tripDate={tripDate}
              onStartFromStory={handleStartFromStory}
              startingStoryId={startingStoryId}
              startDisabled={dateInvalid || loading}
            />
          </article>

          <article className="plan-mode-card" data-coach="mode-anchor">
            <span className="plan-mode-badge">3</span>
            <h3 className="plan-mode-title">꼭 가고 싶은 곳 중심</h3>
            <p className="plan-mode-desc">
              공연·예약처럼 시각이 정해진 장소를 등록하면, 앞뒤 빈 시간을 자동으로 채워 드려요.
            </p>
            {anchorCandidate ? (
              <div className="anchor-selected-summary">
                <span className="anchor-selected-name">📌 {anchorCandidate.placeName}</span>
                <input
                  type="time"
                  className="anchor-selected-time"
                  value={anchorTime}
                  onChange={(e) => setAnchorTime(e.target.value)}
                />
                <button
                  type="button"
                  className="icon-btn danger"
                  aria-label="앵커 선택 해제"
                  onClick={handleClearAnchor}
                >
                  🗑️
                </button>
              </div>
            ) : (
              <>
                <div className="reco-search-form">
                  <input
                    type="text"
                    className="reco-query-input"
                    placeholder="예: DDP, 청룡사(안성)"
                    value={anchorQuery}
                    onChange={(e) => setAnchorQuery(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleAnchorSearch();
                      }
                    }}
                  />
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={handleAnchorSearch}
                    disabled={anchorSearchLoading || !anchorQuery.trim()}
                  >
                    {anchorSearchLoading ? '찾는 중...' : '🔎 이름으로 검색'}
                  </button>
                </div>
                {anchorResolveError && <div className="error-msg">❌ {anchorResolveError}</div>}
                {anchorResults !== null && (
                  anchorResults.length === 0 ? (
                    <p className="empty-state">'{anchorQuery}'(으)로 찾은 장소가 없어요. 다른 이름으로 검색해보세요.</p>
                  ) : (
                    <div className="reco-grid">
                      {anchorResults.map((c) => {
                        const key = anchorCandidateKey(c);
                        return (
                          <RecommendationCard
                            key={key}
                            candidate={c}
                            onAdd={handleSelectAnchor}
                            adding={anchorResolvingKey === key}
                            addLabel="📌 이 장소를 중심으로"
                            addingLabel="확인하는 중..."
                          />
                        );
                      })}
                    </div>
                  )
                )}
              </>
            )}
            {anchorCandidate && (
              <button className="btn-primary btn-start" type="button" onClick={handleAnchorStart} disabled={loading || !canSubmit}>
                {loading ? '일정 준비 중...' : `📌 ${anchorCandidate.placeName} 기준으로 시작`}
              </button>
            )}
          </article>
        </div>

        {error && <div className="error-msg">❌ {error}</div>}
      </form>

      <p className="create-trip-hint">
        당일치기 중심으로, 날씨·혼잡·동선 변수를 미리 알려주고 대안을 고르면 그 기록이 다른 여행자 참고가 됩니다.
      </p>

      {ongoingLoading ? (
        <div className="draft-resume-banner draft-resume-loading">
          <p>진행 중인 당일치기를 확인하는 중…</p>
        </div>
      ) : ongoingTrips.length > 0 ? (
        <div className="draft-resume-panel">
          <div className="draft-resume-panel-head">
            <strong>진행 중인 여행이 있어요</strong>
            <p>날짜별 당일치기를 이어 보거나, 위에서 새 여행을 시작할 수 있어요.</p>
          </div>
          <ul className="draft-resume-list">
            {ongoingTrips.map((trip, index) => (
              <li key={trip.itineraryId} className="draft-resume-row">
                <div className="draft-resume-row-main">
                  <span className="draft-resume-day">당일치기 {index + 1}</span>
                  <span className="draft-resume-date">{formatDraftDate(trip.startDate)}</span>
                  {trip.regionDisplayName && (
                    <span className="draft-resume-region">{trip.regionDisplayName}</span>
                  )}
                  <span className="draft-resume-meta">
                    {trip.placeCount ?? 0}곳
                    {trip.companionType && COMPANION_LABEL[trip.companionType]
                      ? ` · ${COMPANION_LABEL[trip.companionType]}`
                      : ''}
                  </span>
                </div>
                <div className="draft-resume-row-actions">
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={() => onResumeDraft?.(trip.itineraryId)}
                  >
                    이어하기
                  </button>
                  <button
                    type="button"
                    className="icon-btn danger draft-resume-delete"
                    aria-label="일정 삭제"
                    disabled={deletingDraftId === trip.itineraryId}
                    onClick={() => handleDeleteDraft(trip)}
                  >
                    {deletingDraftId === trip.itineraryId ? '…' : '🗑️'}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : draftItineraryId && onResumeDraft ? (
        <div className="draft-resume-banner">
          <div>
            <strong>진행 중인 여행이 있어요</strong>
            <p>이어서 일정을 보거나, 위에서 새 여행을 시작할 수 있어요.</p>
          </div>
          <button type="button" className="btn-primary" onClick={() => onResumeDraft(draftItineraryId)}>
            이어하기
          </button>
        </div>
      ) : null}

      {!situationDismissed && (
        <NudgeCard
          situation={situation}
          loading={situationLoading}
          onDismiss={() => setSituationDismissed(true)}
          onAction={() => {
            document.getElementById('trip-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }}
        />
      )}

      <CoachTour
        tourId="home"
        steps={[
          { selector: '[data-coach="region"]', title: '먼저 지역을 고르세요', body: '시/도 → 시/군/구 순서로 오늘 다녀올 곳을 선택해요.' },
          { selector: '[data-coach="modes"]', title: '일정 짜는 방법은 세 가지예요', body: '스마트 자동 · 다른 사람 일정 참고 · 꼭 가고 싶은 곳 중심. 하나만 고르면 됩니다.' },
          { selector: '[data-coach="mode-smart"]', title: '처음이면 여기를 누르세요', body: '식당 2곳, 카페 1곳, 일정 4곳을 지금 시각과 상관없이 채워 드려요.' },
        ]}
      />
    </div>
  );
}
