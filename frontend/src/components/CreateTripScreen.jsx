import { useState, useEffect, useMemo } from 'react';
import PinwheelHero from './PinwheelHero';
import TripStoryFeed from './TripStoryFeed';
import SituationBanner, { loadSituationByGeolocation, maybeNotifySituation } from './SituationBanner';
import * as api from '../api/windmillApi';
import { COMPANION_TYPE_OPTIONS } from '../constants';

function todayIso() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export default function CreateTripScreen({
  onCreate,
  onStartFromStory,
  loading,
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
  const [withPet, setWithPet] = useState(false);
  const [situation, setSituation] = useState(null);
  const [situationLoading, setSituationLoading] = useState(true);
  const [situationDismissed, setSituationDismissed] = useState(false);

  useEffect(() => {
    api.getRegions()
      .then((list) => {
        setRegions(list);
        if (list.length > 0) setSidoCode(list[0].sidoCode);
      })
      .catch((e) => setRegionsError(e.message));
  }, []);

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
  const canSubmit = Boolean(signguFullCode && tripDate && !dateBeforeToday);

  function handleDateChange(value) {
    setDateTouched(true);
    if (!value) {
      setTripDate('');
      return;
    }
    // 당일치기: 하루 날짜만 허용 (과거면 오늘로 보정)
    setTripDate(value < today ? today : value);
  }

  function handleSubmit(e) {
    e.preventDefault();
    setDateTouched(true);
    if (!canSubmit) return;
    // 당일치기 강제: startDate === endDate
    onCreate({
      signguFullCode,
      startDate: tripDate,
      endDate: tripDate,
      companionType,
      withPet,
    });
  }

  function handleStartFromStory(story) {
    setDateTouched(true);
    if (dateInvalid || !onStartFromStory) return;
    onStartFromStory(story, tripDate);
  }

  return (
    <div className="create-trip-screen">
      <PinwheelHero />
      <h1 className="brand-title">바람따라</h1>
      <p className="brand-tagline">당일치기 여행의 날씨·혼잡·동선 변수를 미리 알려주고, 대안을 쌓아 모두가 참고하는 가이드</p>

      {!situationDismissed && (
        <SituationBanner
          situation={situation}
          loading={situationLoading}
          onDismiss={() => setSituationDismissed(true)}
        />
      )}

      {draftItineraryId && onResumeDraft && (
        <div className="draft-resume-banner">
          <div>
            <strong>진행 중인 여행이 있어요</strong>
            <p>이어서 일정을 보거나, 아래에서 새 여행을 시작할 수 있어요.</p>
          </div>
          <button type="button" className="btn-primary" onClick={onResumeDraft}>
            이어하기
          </button>
        </div>
      )}

      <form className="trip-form" onSubmit={handleSubmit}>
        <div className="trip-form-row">
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

        {/* 지역 선택 직후: 해당 지역 우선 · 카드 클릭 시 그 일정 그대로 시작 (아래 여행 날짜 적용) */}
        <TripStoryFeed
          signguFullCode={signguFullCode}
          regionLabel={regionLabel}
          tripDate={tripDate}
          onStartFromStory={handleStartFromStory}
          startingStoryId={startingStoryId}
          startDisabled={dateInvalid || loading}
        />

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
                onClick={() => setCompanionType(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <label className="trip-form-checkbox">
            <input type="checkbox" checked={withPet} onChange={(e) => setWithPet(e.target.checked)} />
            🐾 반려동물과 함께해요
          </label>
        </div>

        <button className="btn-primary btn-start" type="submit" disabled={loading || !canSubmit}>
          {loading ? '일정 준비 중...' : '🌬️ 당일치기 시작하기'}
        </button>

        {error && <div className="error-msg">❌ {error}</div>}
      </form>

      <p className="create-trip-hint">
        당일치기 중심으로, 날씨·혼잡·동선 변수를 미리 알려주고 대안을 고르면 그 기록이 다른 여행자 참고가 됩니다.
      </p>
    </div>
  );
}
