import { useState, useEffect } from 'react';
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

const COMPANION_LABEL = Object.fromEntries(COMPANION_TYPE_OPTIONS.map((o) => [o.value, o.label]));

function formatDateRange(start, end) {
  if (!start) return null;
  return start === end ? start : `${start} ~ ${end}`;
}

export default function CreateTripScreen({ onCreate, loading, error, draftItineraryId, onResumeDraft }) {
  const [regions, setRegions] = useState([]);
  const [regionsError, setRegionsError] = useState(null);
  const [sidoCode, setSidoCode] = useState('');
  const [signguFullCode, setSignguFullCode] = useState('');
  const [tripDate, setTripDate] = useState(todayIso());
  const [companionType, setCompanionType] = useState('SOLO');
  const [withPet, setWithPet] = useState(false);
  const [highlights, setHighlights] = useState([]);
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

  useEffect(() => {
    if (!signguFullCode) {
      setHighlights([]);
      return;
    }
    let cancelled = false;
    api.getRegionTripHighlights(signguFullCode)
      .then((list) => { if (!cancelled) setHighlights(list); })
      .catch(() => { if (!cancelled) setHighlights([]); });
    return () => { cancelled = true; };
  }, [signguFullCode]);

  const selectedSido = regions.find((r) => r.sidoCode === sidoCode);
  const signguOptions = selectedSido?.signgus || [];

  useEffect(() => {
    if (signguOptions.length > 0 && !signguOptions.some((s) => s.signguFullCode === signguFullCode)) {
      setSignguFullCode(signguOptions[0].signguFullCode);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidoCode, regions]);

  const today = todayIso();
  const dateBeforeToday = tripDate && tripDate < today;
  const canSubmit = signguFullCode && tripDate && !dateBeforeToday;

  function handleDateChange(value) {
    setTripDate(value < today ? today : value);
  }

  function handleSubmit(e) {
    e.preventDefault();
    if (!canSubmit) return;
    // 당일치기: startDate === endDate
    onCreate({ signguFullCode, startDate: tripDate, endDate: tripDate, companionType, withPet });
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

      <TripStoryFeed />

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

        <div className="trip-form-row">
          <label className="trip-form-label">여행 날짜 <span className="trip-form-hint-inline">당일치기</span></label>
          <input
            type="date"
            className="trip-form-date-single"
            value={tripDate}
            min={today}
            onChange={(e) => handleDateChange(e.target.value)}
            required
          />
          {dateBeforeToday && <div className="error-msg">❌ 여행일은 오늘 이후여야 해요</div>}
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

      {highlights.length > 0 && (
        <div className="region-highlights">
          <h3 className="region-highlights-title">👍 이 지역 당일치기 추천</h3>
          {highlights.map((h, i) => (
            <div key={i} className="region-highlight-card">
              {h.thumbnailUrl && (
                <img className="region-highlight-thumb" src={h.thumbnailUrl} alt="" loading="lazy" />
              )}
              <div className="region-highlight-body">
                <div className="region-highlight-meta">
                  {formatDateRange(h.startDate, h.endDate) && (
                    <span className="region-highlight-chip">🗓️ {formatDateRange(h.startDate, h.endDate)}</span>
                  )}
                  {h.companionType && COMPANION_LABEL[h.companionType] && (
                    <span className="region-highlight-chip">{COMPANION_LABEL[h.companionType]}</span>
                  )}
                  {h.withPet && <span className="region-highlight-chip">🐾 반려동물 동반</span>}
                </div>
                {h.placeNames.length > 0 && (
                  <p className="region-highlight-places">{h.placeNames.join(' · ')}</p>
                )}
                {h.overallNote && <p className="region-highlight-note">"{h.overallNote}"</p>}
              </div>
            </div>
          ))}
        </div>
      )}

      <p className="create-trip-hint">
        당일치기 중심으로, 날씨·혼잡·동선 변수를 미리 알려주고 대안을 고르면 그 기록이 다른 여행자 참고가 됩니다.
      </p>
    </div>
  );
}
