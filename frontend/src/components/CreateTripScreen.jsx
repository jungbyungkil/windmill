import { useState, useEffect, useMemo, useRef } from 'react';
import BrandMark from './BrandMark';
import PinwheelLoader from './PinwheelLoader';
import VisitTimePicker from './VisitTimePicker';
import TripStoryFeed from './TripStoryFeed';
import RecommendationCard from './RecommendationCard';
import { RecommendIcon, PickIcon, TrashIcon } from './Icons';
import * as api from '../api/windmillApi';
import {
  COMPANION_TYPE_OPTIONS,
  AGE_GROUP_OPTIONS,
  CHILD_AGE_OPTIONS,
  FIXED_PARTY_SIZE_BY_COMPANION_TYPE,
  EXTENDED_FAMILY_MIN_SIZE,
  EXTENDED_FAMILY_MAX_SIZE,
} from '../constants';
import { readTravelerProfile, writeTravelerProfile, isChildAgeStale } from '../utils/travelerProfile';

const MAX_SUMMARY_ACCESSIBILITY_LABELS = 2;

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

function isTodayDate(dateStr) {
  if (!dateStr) return false;
  return String(dateStr).slice(0, 10) === todayIso();
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
  // 동반·접근성 저장 프로필(2026-09-12 브리프) - 한 번 입력하면 다음부터 자동으로 채워 넣고
  // 접힌 상태로 시작한다. 마운트 시 1회만 읽고, 이후 갱신은 각 필드 변경 시점에 persistProfile로.
  const [savedProfile] = useState(readTravelerProfile);
  const isFirstVisit = !savedProfile;
  const lastRegionAppliedRef = useRef(false);

  const [regions, setRegions] = useState([]);
  const [regionsError, setRegionsError] = useState(null);
  const [sidoCode, setSidoCode] = useState('');
  const [signguFullCode, setSignguFullCode] = useState('');
  const [tripDate, setTripDate] = useState(todayIso());
  const [dateTouched, setDateTouched] = useState(false);
  const [companionType, setCompanionType] = useState(savedProfile?.companionType || 'SOLO');
  const [partySize, setPartySize] = useState(savedProfile?.totalCount || 1);
  const [partySizeTouched, setPartySizeTouched] = useState(false);
  const [adultAgeGroup, setAdultAgeGroup] = useState(savedProfile?.adultAgeGroup || 'THIRTIES');
  const [childAges, setChildAges] = useState(() => [...(savedProfile?.childrenAges || [])]);
  const [withPet, setWithPet] = useState(Boolean(savedProfile?.accessibility?.pet));
  const [strollerFriendly, setStrollerFriendly] = useState(Boolean(savedProfile?.accessibility?.stroller));
  const [accessibleFriendly, setAccessibleFriendly] = useState(Boolean(savedProfile?.accessibility?.barrierFree));
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
  const [storyFeedAvailable, setStoryFeedAvailable] = useState(false);
  // 저장값이 없으면(첫 방문) 펼친 채로 시작. 저장값이 있어도 동반 자녀 나이가 180일 넘게
  // 지났으면 틀린 값일 수 있어 재확인을 유도하려고 펼친 채로 시작한다(브리프 4절).
  const [detailsOpen, setDetailsOpen] = useState(() => isFirstVisit || isChildAgeStale(savedProfile));
  const [otherWaysOpen, setOtherWaysOpen] = useState(false);

  useEffect(() => {
    api.getRegions()
      .then((list) => {
        setRegions(list);
        if (list.length > 0) {
          const savedSidoName = savedProfile?.lastRegion?.sido;
          const match = savedSidoName ? list.find((r) => r.sidoName === savedSidoName) : null;
          setSidoCode((match || list[0]).sidoCode);
        }
      })
      .catch((e) => setRegionsError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const selectedSido = regions.find((r) => r.sidoCode === sidoCode);
  const signguOptions = selectedSido?.signgus || [];

  useEffect(() => {
    if (signguOptions.length === 0 || signguOptions.some((s) => s.signguFullCode === signguFullCode)) return;
    let next = signguOptions[0];
    // 저장된 마지막 지역 기본값 주입은 최초 1회만 - 이후 사용자가 시/도를 직접 바꾸면
    // 그 시/도의 첫 시군구로만 자동 채운다(다른 지역의 저장값을 계속 끌어오지 않도록).
    if (!lastRegionAppliedRef.current) {
      const savedSigunguName = savedProfile?.lastRegion?.sigungu;
      const match = savedSigunguName ? signguOptions.find((s) => s.signguName === savedSigunguName) : null;
      if (match) next = match;
    }
    lastRegionAppliedRef.current = true;
    setSignguFullCode(next.signguFullCode);
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

  // 접힌 헤더 요약 칩: 동반유형 · 연령대 (+ 체크된 접근성 옵션 최대 2개). 값이 자주 바뀌지
  // 않는 고정 속성만 담고, 매번 확인하는 자녀 나이는 요약에서 뺀다(브리프 5-1절).
  const detailsSummary = useMemo(() => {
    const companion = COMPANION_LABEL[companionType] || '';
    const age = AGE_GROUP_OPTIONS.find((o) => o.value === adultAgeGroup)?.label || '';
    const accessibilityLabels = [
      withPet ? '반려동물' : null,
      strollerFriendly ? '유모차' : null,
      accessibleFriendly ? '무장애' : null,
    ].filter(Boolean).slice(0, MAX_SUMMARY_ACCESSIBILITY_LABELS);
    return [companion, age, ...accessibilityLabels].filter(Boolean).join(' · ');
  }, [companionType, adultAgeGroup, withPet, strollerFriendly, accessibleFriendly]);

  /**
   * 동반·접근성 입력을 로컬에 저장한다(2026-09-12 브리프) - 각 필드 변경 핸들러에서 그
   * 시점의 최신 값 + 방금 바뀐 값(overrides)을 넘겨 호출한다. 마운트 시 자동 저장은 하지
   * 않는다 - 매번 열 때마다 저장값을 그대로 다시 쓰면 savedAt이 계속 갱신되어 자녀 나이
   * 180일 경과 판정(4절)이 무력화되기 때문에, 반드시 사용자가 실제로 값을 바꿀 때만 호출한다.
   */
  function persistProfile(overrides = {}) {
    writeTravelerProfile({
      companionType,
      totalCount: partySize,
      accessibility: { pet: withPet, stroller: strollerFriendly, barrierFree: accessibleFriendly },
      adultAgeGroup,
      childrenAges: childAges,
      lastRegion: selectedSido && signguFullCode
        ? { sido: selectedSido.sidoName, sigungu: signguOptions.find((s) => s.signguFullCode === signguFullCode)?.signguName }
        : undefined,
      ...overrides,
    });
  }

  function handleCompanionTypeChange(value) {
    setCompanionType(value);
    const fixedSize = FIXED_PARTY_SIZE_BY_COMPANION_TYPE[value];
    let nextPartySize = partySize;
    if (fixedSize != null) {
      setPartySize(fixedSize);
      nextPartySize = fixedSize;
    } else if (partySize < EXTENDED_FAMILY_MIN_SIZE || partySize > EXTENDED_FAMILY_MAX_SIZE) {
      setPartySize(EXTENDED_FAMILY_MIN_SIZE);
      nextPartySize = EXTENDED_FAMILY_MIN_SIZE;
    }
    setPartySizeTouched(false);
    persistProfile({ companionType: value, totalCount: nextPartySize });
  }

  function handleSidoChange(nextSidoCode) {
    setSidoCode(nextSidoCode);
    const sido = regions.find((r) => r.sidoCode === nextSidoCode);
    const firstSigungu = sido?.signgus?.[0];
    persistProfile({
      lastRegion: sido && firstSigungu ? { sido: sido.sidoName, sigungu: firstSigungu.signguName } : undefined,
    });
  }

  function handleSignguChange(nextSignguFullCode) {
    setSignguFullCode(nextSignguFullCode);
    const sigungu = signguOptions.find((s) => s.signguFullCode === nextSignguFullCode);
    if (selectedSido && sigungu) {
      persistProfile({ lastRegion: { sido: selectedSido.sidoName, sigungu: sigungu.signguName } });
    }
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
    setChildAges((prev) => {
      if (prev.length >= maxChildren) return prev;
      const next = [...prev, 10];
      persistProfile({ childrenAges: next });
      return next;
    });
  }

  function handleChangeChildAge(index, age) {
    setChildAges((prev) => {
      const next = prev.map((a, i) => (i === index ? age : a));
      persistProfile({ childrenAges: next });
      return next;
    });
  }

  function handleRemoveChild(index) {
    setChildAges((prev) => {
      const next = prev.filter((_, i) => i !== index);
      persistProfile({ childrenAges: next });
      return next;
    });
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

  const resumeBlock = ongoingLoading ? (
    <div className="draft-resume-banner draft-resume-loading">
      <p>진행 중인 당일치기를 확인하는 중…</p>
    </div>
  ) : ongoingTrips.length > 0 ? (
    <div className="draft-resume-panel">
      <div className="draft-resume-panel-head">
        <strong>진행 중인 여행이 있어요</strong>
        <p>이어서 보거나, 위에서 새 여행을 시작할 수 있어요.</p>
      </div>
      <ul className="draft-resume-list">
        {ongoingTrips.map((trip, index) => {
          const isToday = isTodayDate(trip.startDate);
          return (
            <li
              key={trip.itineraryId}
              className={`draft-resume-row${isToday ? ' draft-resume-row--today' : ''}`}
            >
              <div className="draft-resume-row-main">
                <span className="draft-resume-day">당일치기 {index + 1}</span>
                <span className="draft-resume-date-group">
                  <span className="draft-resume-date">{formatDraftDate(trip.startDate)}</span>
                  {isToday && <span className="draft-resume-today-tag">오늘</span>}
                </span>
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
                  {deletingDraftId === trip.itineraryId ? '…' : '삭제'}
                </button>
              </div>
            </li>
          );
        })}
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
  ) : null;

  return (
    <div className="create-trip-screen">
      {loading && (
        <PinwheelLoader
          message={
            loadingStage
              || (anchorCandidate
                ? `${anchorCandidate.placeName} 기준으로 장소를 찾고 있어요...`
                : '이 지역 축제와 인기 스팟으로 오전·오후 일정을 만들고 있어요...')
          }
        />
      )}

      <BrandMark />

      <form id="trip-form" className="trip-form" onSubmit={handleSubmit}>
        <div className="trip-form-row">
          <label className="trip-form-label">여행 지역</label>
          {regionsError && <div className="error-msg">❌ 지역 목록을 불러오지 못했어요: {regionsError}</div>}
          <div className="trip-form-region-selects">
            <select value={sidoCode} onChange={(e) => handleSidoChange(e.target.value)} disabled={regions.length === 0}>
              {regions.map((r) => (
                <option key={r.sidoCode} value={r.sidoCode}>{r.sidoName}</option>
              ))}
            </select>
            <select value={signguFullCode} onChange={(e) => handleSignguChange(e.target.value)} disabled={signguOptions.length === 0}>
              {signguOptions.map((s) => (
                <option key={s.signguFullCode} value={s.signguFullCode}>{s.signguName}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="trip-form-row">
          <div className="trip-form-date-row">
            <label className="trip-form-label" htmlFor="trip-date">
              여행 날짜
            </label>
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
            <p className="trip-form-date-help">당일치기만 지원해요.</p>
          </div>
          <span id="trip-date-help" className="sr-only">하루 날짜만 선택하세요</span>
          {dateTouched && !tripDate && (
            <div className="error-msg">❌ 여행 날짜를 선택해 주세요</div>
          )}
          {dateBeforeToday && (
            <div className="error-msg">❌ 여행일은 오늘 이후여야 해요</div>
          )}
        </div>

        <div className="trip-form-disclose">
          <button
            type="button"
            className="trip-form-disclose-btn"
            aria-expanded={detailsOpen}
            onClick={() => setDetailsOpen((open) => !open)}
          >
            <span className="trip-form-disclose-title">동반 · 접근성</span>
            <span className="trip-form-disclose-meta">{detailsSummary}</span>
            {detailsOpen ? (
              <span className="trip-form-disclose-chevron" aria-hidden="true">▾</span>
            ) : (
              <span className="trip-form-disclose-edit">변경</span>
            )}
          </button>
          {detailsOpen && (
            <div className="trip-form-disclose-body">
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
                const next = Number.isNaN(n) ? '' : n;
                setPartySize(next);
                if (next !== '') persistProfile({ totalCount: next });
              }}
              onBlur={() => setPartySizeTouched(true)}
            />
            <span className="trip-form-hint-inline">
              {isExtendedFamily ? '명 · 5~9명까지 적을 수 있어요' : '명 · 동반 유형에 맞춰 표시돼요'}
            </span>
          </label>
          {isExtendedFamily && partySizeTouched && partySizeError && (
            <div className="error-msg">❌ {partySizeError}</div>
          )}
          <div className="trip-form-checkbox-row">
            <label className="trip-form-checkbox">
              <input
                type="checkbox"
                checked={withPet}
                onChange={(e) => {
                  setWithPet(e.target.checked);
                  persistProfile({ accessibility: { pet: e.target.checked, stroller: strollerFriendly, barrierFree: accessibleFriendly } });
                }}
              />
              반려동물
            </label>
            <label className="trip-form-checkbox" title="유모차 이용 가능한 곳을 우선 추천해요">
              <input
                type="checkbox"
                checked={strollerFriendly}
                onChange={(e) => {
                  setStrollerFriendly(e.target.checked);
                  persistProfile({ accessibility: { pet: withPet, stroller: e.target.checked, barrierFree: accessibleFriendly } });
                }}
              />
              유모차
            </label>
            <label className="trip-form-checkbox" title="장애인 동반 - 무장애 시설을 우선 추천해요">
              <input
                type="checkbox"
                checked={accessibleFriendly}
                onChange={(e) => {
                  setAccessibleFriendly(e.target.checked);
                  persistProfile({ accessibility: { pet: withPet, stroller: strollerFriendly, barrierFree: e.target.checked } });
                }}
              />
              무장애
            </label>
          </div>
        </div>

        <div className="trip-form-row">
          <label className="trip-form-label">성인 연령대</label>
          <div className="reco-tag-row">
            {AGE_GROUP_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                className={`tag ${adultAgeGroup === opt.value ? 'selected' : ''}`}
                onClick={() => {
                  setAdultAgeGroup(opt.value);
                  persistProfile({ adultAgeGroup: opt.value });
                }}
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
                  <TrashIcon size={16} />
                </button>
              </div>
            ))}
          </div>
        </div>
        {isFirstVisit && (
          <p className="trip-form-disclose-hint">한 번 선택하면 다음부터 자동으로 채워져요.</p>
        )}
            </div>
          )}
        </div>

        <button className="btn-primary btn-start" type="submit" disabled={loading || !canSubmit}>
          {loading ? '일정 준비 중...' : '스마트 동선으로 시작'}
        </button>

        <div className={`trip-form-disclose trip-alt-start ${otherWaysOpen ? 'is-open' : ''}`}>
          <button
            type="button"
            className="trip-alt-start-btn"
            aria-expanded={otherWaysOpen}
            aria-label="추천 코스 또는 직접 선택"
            onClick={() => setOtherWaysOpen((open) => !open)}
          >
            <span className="trip-alt-start-choices">
              <span className="trip-alt-start-choice"><RecommendIcon size={16} /> 추천 코스</span>
              <span className="trip-alt-start-or">또는</span>
              <span className="trip-alt-start-choice"><PickIcon size={16} /> 직접 선택</span>
            </span>
            <span className="trip-alt-start-chevron" aria-hidden="true">{otherWaysOpen ? '▾' : '▸'}</span>
          </button>
          {otherWaysOpen && (
            <div className="trip-form-disclose-body">
        <div className="plan-mode-section">
          <article className="plan-mode-card" hidden={!storyFeedAvailable}>
            <h3 className="plan-mode-title"><RecommendIcon size={18} /> 추천 코스</h3>
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
              onAvailabilityChange={setStoryFeedAvailable}
            />
          </article>

          <article className="plan-mode-card">
            <h3 className="plan-mode-title"><PickIcon size={18} /> 직접 선택</h3>
            <p className="plan-mode-desc">
              공연·예약처럼 시각이 정해진 장소를 등록하면, 앞뒤 빈 시간을 자동으로 채워 드려요.
            </p>
            {anchorCandidate ? (
              <div className="anchor-selected-summary">
                <span className="anchor-selected-name"><PickIcon size={16} /> {anchorCandidate.placeName}</span>
                <VisitTimePicker
                  className="anchor-selected-time"
                  value={anchorTime}
                  onChange={setAnchorTime}
                  aria-label="고정 일정 시각"
                />
                <button
                  type="button"
                  className="icon-btn danger"
                  aria-label="앵커 선택 해제"
                  onClick={handleClearAnchor}
                >
                  <TrashIcon size={16} />
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
                            addLabel={<><PickIcon size={14} /> 이 장소를 중심으로</>}
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
                {loading ? '일정 준비 중...' : <><PickIcon size={16} /> {anchorCandidate.placeName} 기준으로 시작</>}
              </button>
            )}
          </article>
        </div>
            </div>
          )}
        </div>

        {error && <div className="error-msg">❌ {error}</div>}
      </form>

      {resumeBlock}
    </div>
  );
}
