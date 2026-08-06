import { useState, useEffect, useCallback, useRef } from 'react';
import useSession from './hooks/useSession';
import * as api from './api/windmillApi';
import CreateTripScreen from './components/CreateTripScreen';
import SmartPlanScreen from './components/SmartPlanScreen';
import CategoryRecommendScreen from './components/CategoryRecommendScreen';
import AutoPlanScreen from './components/AutoPlanScreen';
import PinwheelHero from './components/PinwheelHero';
import WeatherBanner from './components/WeatherBanner';
import FestivalBanner from './components/FestivalBanner';
import ItineraryList from './components/ItineraryList';
import DayTabs from './components/DayTabs';
import RecommendationSearch from './components/RecommendationSearch';
import AlternativesPanel from './components/AlternativesPanel';
import DocentModal from './components/DocentModal';
import TripRecordModal from './components/TripRecordModal';
import SharedItineraryScreen from './components/SharedItineraryScreen';
import './App.css';

const TRIGGER_POLL_MS = 90 * 1000;

function readShareTokenFromHash() {
  const m = window.location.hash.match(/^#\/share\/([A-Za-z0-9_-]+)/);
  return m ? m[1] : null;
}

function toLocalYmd(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function enumerateTripDates(startDate, endDate) {
  if (!startDate) return [];
  const dates = [];
  const cursor = new Date(startDate + 'T00:00:00');
  const last = new Date((endDate || startDate) + 'T00:00:00');
  while (cursor <= last) {
    dates.push(toLocalYmd(cursor));
    cursor.setDate(cursor.getDate() + 1);
  }
  return dates;
}

function dayLabelFor(dateStr, tripDates) {
  if (!dateStr || !tripDates?.length) return null;
  const idx = tripDates.indexOf(dateStr);
  if (idx < 0) return dateStr;
  const d = new Date(dateStr + 'T00:00:00');
  return `${idx + 1}일차 (${d.getMonth() + 1}/${d.getDate()})`;
}

export default function App() {
  const { sessionId, itineraryId, setItineraryId } = useSession();

  const [shareToken, setShareToken] = useState(() => readShareTokenFromHash());
  const [itinerary, setItinerary] = useState(null);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);
  /** 핵심: 혼잡↓·동선최적화 스마트 일정 우선 노출. null이면 전체 기간, 문자열이면 해당 일자만 */
  const [showSmartPlan, setShowSmartPlan] = useState(false);
  const [smartPlanDate, setSmartPlanDate] = useState(null);
  const [showCategoryReco, setShowCategoryReco] = useState(false);
  const [showAutoPlan, setShowAutoPlan] = useState(false);

  const [trigger, setTrigger] = useState(null);
  const [weatherItems, setWeatherItems] = useState(null);

  const [recoResults, setRecoResults] = useState(null);
  const [recoLoading, setRecoLoading] = useState(false);
  const [addingContentId, setAddingContentId] = useState(null);
  const [addingFestivalId, setAddingFestivalId] = useState(null);

  const [altOpen, setAltOpen] = useState(false);
  const [altCandidates, setAltCandidates] = useState([]);
  const [altLoading, setAltLoading] = useState(false);
  const [altReason, setAltReason] = useState(null);

  const [docentOpen, setDocentOpen] = useState(false);
  const [docentItem, setDocentItem] = useState(null);
  const [docentPlaceName, setDocentPlaceName] = useState('');
  const [docentScript, setDocentScript] = useState('');
  const [docentAudioUrl, setDocentAudioUrl] = useState(null);
  const [docentLoading, setDocentLoading] = useState(false);
  const [docentError, setDocentError] = useState(null);
  const [docentLang, setDocentLang] = useState('ko');

  const [tripRecordOpen, setTripRecordOpen] = useState(false);
  const [tripSubmitting, setTripSubmitting] = useState(false);
  const [rerouteCount, setRerouteCount] = useState(0);

  const [autoReplacing, setAutoReplacing] = useState(false);
  const [autoReplaceNotice, setAutoReplaceNotice] = useState(null);
  const [rerouteLoading, setRerouteLoading] = useState(false);
  const [optimizeLoading, setOptimizeLoading] = useState(false);
  const [shareBusy, setShareBusy] = useState(false);
  const autoOptimizedRef = useRef(false);

  const [activeDate, setActiveDate] = useState(null);
  const [confirmingDay, setConfirmingDay] = useState(false);

  useEffect(() => {
    function onHash() {
      setShareToken(readShareTokenFromHash());
    }
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // 새로고침 시 저장된 itineraryId로 일정 복원
  useEffect(() => {
    if (!itineraryId) return;
    api.getItinerary(itineraryId)
      .then(setItinerary)
      .catch(() => setItineraryId(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itineraryId]);

  // 일정이 새로 로드되면 여행 시작일을 기본 활성 날짜로
  useEffect(() => {
    if (itinerary?.startDate && !activeDate) {
      setActiveDate(itinerary.startDate);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itinerary?.startDate]);

  // 아직 담기지 않았거나(day 미지정 레거시) 활성 날짜와 다른 항목은 다른 날 페이지에 숨긴다
  const tripDates = itinerary ? enumerateTripDates(itinerary.startDate, itinerary.endDate) : [];
  const visibleItems = itinerary
    ? itinerary.items.filter((i) => (i.visitDate || itinerary.startDate) === activeDate)
    : [];
  const itemCounts = {};
  if (itinerary) {
    tripDates.forEach((d) => { itemCounts[d] = 0; });
    itinerary.items.forEach((i) => {
      const d = i.visitDate || itinerary.startDate;
      if (d) itemCounts[d] = (itemCounts[d] || 0) + 1;
    });
  }
  const activeDayLabel = dayLabelFor(activeDate, tripDates);

  function openSmartPlanForDate(date) {
    setSmartPlanDate(date || null);
    setShowSmartPlan(true);
    setShowCategoryReco(false);
    setShowAutoPlan(false);
  }
  async function handleToggleConfirmDay(date, confirmed) {
    setConfirmingDay(true);
    try {
      const result = await api.confirmDay(itineraryId, date, confirmed);
      setItinerary(result);
    } finally {
      setConfirmingDay(false);
    }
  }

  const refreshTrigger = useCallback(() => {
    if (!itineraryId) return;
    api.getTriggerStatus(itineraryId).then(setTrigger).catch(() => {});
  }, [itineraryId]);

  useEffect(() => {
    if (!itineraryId) return;
    refreshTrigger();
    if (itinerary?.weatherNx && itinerary?.weatherNy) {
      api.getWeather(itinerary.weatherNx, itinerary.weatherNy).then(setWeatherItems).catch(() => setWeatherItems(null));
    }
    const id = setInterval(refreshTrigger, TRIGGER_POLL_MS);
    return () => clearInterval(id);
  }, [itineraryId, itinerary?.weatherNx, itinerary?.weatherNy, refreshTrigger]);

  async function handleCreate(formData) {
    setCreating(true);
    setCreateError(null);
    try {
      const result = await api.createItinerary(sessionId, formData);
      setItinerary(result);
      setItineraryId(result.itineraryId);
      setActiveDate(result.startDate);
      setSmartPlanDate(null); // 전체 기간 스마트 일정
      setShowSmartPlan(true);
      setShowCategoryReco(false);
      setShowAutoPlan(false);
    } catch (e) {
      setCreateError(e.message);
    } finally {
      setCreating(false);
    }
  }

  async function handleUpdateTime(itemId, scheduledTime) {
    const result = await api.updateItem(itineraryId, itemId, { scheduledTime });
    setItinerary(result);
  }

  async function handleMoveDay(itemId, visitDate) {
    const result = await api.updateItem(itineraryId, itemId, { visitDate });
    setItinerary(result);
  }

  async function handleTogglePin(itemId, isPinned) {
    const result = await api.updateItem(itineraryId, itemId, { isPinned });
    setItinerary(result);
  }

  async function handleDeleteItem(itemId) {
    const result = await api.deleteItem(itineraryId, itemId);
    setItinerary(result);
  }

  async function handleSearch({ query, tags }) {
    setRecoLoading(true);
    try {
      const excludeContentIds = itinerary.items.map((i) => i.contentId).filter(Boolean);
      const lastItem = itinerary.items[itinerary.items.length - 1];
      const results = await api.getRecommendations({
        regionCode: itinerary.signguFullCode,
        withPet: itinerary.withPet,
        companionType: itinerary.companionType,
        query,
        tags,
        excludeContentIds,
        // 거리(km) 표시 기준점 - 이미 담긴 마지막 장소 (없으면 거리 없이 반환됨)
        originContentId: lastItem?.contentId,
        originContentTypeId: lastItem?.contentTypeId,
      });
      setRecoResults(results);
    } catch {
      setRecoResults([]);
    } finally {
      setRecoLoading(false);
    }
  }

  async function addCandidateToItinerary(candidate, visitDate = activeDate, isAlternate = false) {
    const result = await api.addItem(itineraryId, {
      contentId: candidate.contentId,
      contentTypeId: candidate.contentTypeId,
      placeName: candidate.placeName,
      thumbnailUrl: candidate.thumbnailUrl,
      tags: candidate.matchedTags,
      crowdRate: candidate.crowdRate,
      visitDate,
      addr1: candidate.addr1,
      tel: candidate.tel,
      useFeeText: candidate.useFeeText,
      isFree: candidate.isFree,
      restDateText: candidate.restDateText,
      category: candidate.category,
      mapX: candidate.mapX,
      mapY: candidate.mapY,
      isAlternate,
    });
    setItinerary(result);
    return result;
  }

  async function handleAddFestival(festival) {
    setAddingFestivalId(festival.contentId);
    try {
      await addCandidateToItinerary(festival);
      refreshTrigger();
    } finally {
      setAddingFestivalId(null);
    }
  }

  async function handleAddRecommendation(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      await addCandidateToItinerary(candidate);
    } finally {
      setAddingContentId(null);
    }
  }

  async function handleRequestAlternatives(avoidHint) {
    setAltOpen(true);
    setAltLoading(true);
    setAltReason(null);
    try {
      const { candidates, reason } = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      setAltCandidates(candidates);
      setAltReason(reason || (avoidHint === 'HEAT' ? 'HEAT_ALTERNATIVE' : avoidHint === 'WEATHER' ? 'RAIN_ALTERNATIVE' : null));
    } catch {
      setAltCandidates([]);
    } finally {
      setAltLoading(false);
    }
  }

  async function handleAddAlternative(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      await addCandidateToItinerary(candidate, activeDate, true);
      setRerouteCount((n) => n + 1);
    } finally {
      setAddingContentId(null);
    }
  }

  // 자동 재배치: 트리거로 영향받은 첫 항목을 최상위 대안으로 자동 교체 (수동 "새 코스 추천받기"와 별도 액션)
  async function handleAutoReplace(avoidHint) {
    setAutoReplacing(true);
    setAutoReplaceNotice(null);
    try {
      const { candidates, reason } = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      const rainNote = reason === 'RAIN_ALTERNATIVE'
        ? ' (비 예보로 실내 코스를 추천했어요)'
        : reason === 'HEAT_ALTERNATIVE'
          ? ' (폭염으로 실내 코스를 추천했어요)'
          : '';
      if (candidates.length === 0) {
        setAutoReplaceNotice('지금은 자동으로 바꿀 대안이 없어요.');
        return;
      }
      const top = candidates[0];
      const affectedId = trigger?.affectedItemIds?.[0];
      const affectedItem = affectedId ? itinerary.items.find((i) => i.itemId === affectedId) : null;

      if (affectedItem) {
        await api.deleteItem(itineraryId, affectedItem.itemId);
        const result = await api.addItem(itineraryId, {
          contentId: top.contentId,
          contentTypeId: top.contentTypeId,
          placeName: top.placeName,
          thumbnailUrl: top.thumbnailUrl,
          scheduledTime: affectedItem.scheduledTime,
          tags: top.matchedTags,
          crowdRate: top.crowdRate,
          // 교체 대상이었던 항목이 속했던 날짜를 그대로 유지
          visitDate: affectedItem.visitDate || itinerary.startDate,
          addr1: top.addr1,
          tel: top.tel,
          useFeeText: top.useFeeText,
          isFree: top.isFree,
          restDateText: top.restDateText,
          category: top.category,
          mapX: top.mapX,
          mapY: top.mapY,
          isAlternate: true,
        });
        setItinerary(result);
        setAutoReplaceNotice(`"${affectedItem.placeName}"을(를) "${top.placeName}"(으)로 자동 교체했어요.${rainNote}`);
      } else {
        await addCandidateToItinerary(top, activeDate, true);
        setAutoReplaceNotice(`"${top.placeName}"을(를) 일정에 자동으로 추가했어요.${rainNote}`);
      }
      setRerouteCount((n) => n + 1);
      refreshTrigger();
    } catch (e) {
      setAutoReplaceNotice(`자동 교체 실패: ${e.message}`);
    } finally {
      setAutoReplacing(false);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
  }

  /**
   * 비/폭염: 영향 받은 야외 일정 전체를 실내 대안 동선으로 교체.
   * 기존 방문 시각은 유지해 타임라인 리듬을 살린다.
   */
  async function handleRerouteSchedule(avoidHint) {
    setRerouteLoading(true);
    setAutoReplaceNotice(null);
    try {
      const { candidates, reason } = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      const note = reason === 'RAIN_ALTERNATIVE'
        ? '비 소식에 맞춰 실내 일정으로 바꿨어요.'
        : reason === 'HEAT_ALTERNATIVE'
          ? '폭염 소식에 맞춰 실내 일정으로 바꿨어요.'
          : '대체 일정으로 바꿨어요.';

      if (!candidates?.length) {
        setAutoReplaceNotice('지금은 바꿀 실내 일정이 없어요. 후보만 먼저 볼게요.');
        await handleRequestAlternatives(avoidHint);
        return;
      }

      const affectedIds = (trigger?.affectedItemIds || []).map(Number);
      const affectedItems = itinerary.items.filter((i) => affectedIds.includes(Number(i.itemId)));
      const targets = affectedItems.length > 0
        ? affectedItems
        : itinerary.items.filter((i) => (i.visitDate || itinerary.startDate) === activeDate);

      if (targets.length === 0) {
        setAltCandidates(candidates);
        setAltOpen(true);
        setAutoReplaceNotice('교체할 야외 일정이 없어 후보만 보여드려요.');
        return;
      }

      let result = itinerary;
      const used = new Set();
      for (let i = 0; i < targets.length; i++) {
        const target = targets[i];
        const next = candidates.find((c) => c.contentId && !used.has(c.contentId)
          && !result.items.some((it) => it.contentId === c.contentId));
        if (!next) break;
        used.add(next.contentId);
        await api.deleteItem(itineraryId, target.itemId);
        result = await api.addItem(itineraryId, {
          contentId: next.contentId,
          contentTypeId: next.contentTypeId,
          placeName: next.placeName,
          thumbnailUrl: next.thumbnailUrl,
          scheduledTime: target.scheduledTime,
          tags: next.matchedTags?.length ? next.matchedTags : ['#실내'],
          crowdRate: next.crowdRate,
          visitDate: target.visitDate || itinerary.startDate,
          addr1: next.addr1,
          tel: next.tel,
          useFeeText: next.useFeeText,
          isFree: next.isFree,
          restDateText: next.restDateText,
          category: next.category,
          mapX: next.mapX,
          mapY: next.mapY,
          isAlternate: true,
        });
      }

      setItinerary(result);
      setRerouteCount((n) => n + targets.length);
      setAutoReplaceNotice(`${note} (${used.size}곳 교체)`);
      refreshTrigger();
    } catch (e) {
      setAutoReplaceNotice(`일정 교체 실패: ${e.message}`);
    } finally {
      setRerouteLoading(false);
      setTimeout(() => setAutoReplaceNotice(null), 6000);
    }
  }

  function handleGenerateSmartPlan() {
    // 특정 일자만 짜기면 date 전달, 여행 생성 직후면 전체 기간
    return api.getSmartPlan(itineraryId, {
      placeCount: smartPlanDate ? 5 : 0,
      date: smartPlanDate || undefined,
    });
  }

  async function handleConfirmSmartPlan(selected) {
    let result = itinerary;
    const fallbackDate = smartPlanDate || activeDate || itinerary.startDate;
    for (const candidate of selected) {
      result = await api.addItem(itineraryId, {
        contentId: candidate.contentId,
        contentTypeId: candidate.contentTypeId,
        placeName: candidate.placeName,
        thumbnailUrl: candidate.thumbnailUrl,
        scheduledTime: candidate.suggestedTime,
        tags: candidate.matchedTags,
        crowdRate: candidate.crowdRate,
        visitDate: candidate.visitDate || fallbackDate,
        addr1: candidate.addr1,
        tel: candidate.tel,
        useFeeText: candidate.useFeeText,
        isFree: candidate.isFree,
        restDateText: candidate.restDateText,
        category: candidate.category,
        mapX: candidate.mapX,
        mapY: candidate.mapY,
      });
    }
    setItinerary(result);
    setShowSmartPlan(false);
    setSmartPlanDate(null);
    // 다일 담기 후 1일차로 이동해 바로 관리
    if (result?.startDate) setActiveDate(result.startDate);
  }

  function handleGenerateAutoPlan(tags) {
    return api.getAutoPlan(itineraryId, { tags, placeCount: 5 });
  }

  async function handleConfirmAutoPlan(selected) {
    let result = itinerary;
    const visitDate = activeDate || itinerary.startDate;
    for (const candidate of selected) {
      result = await api.addItem(itineraryId, {
        contentId: candidate.contentId,
        contentTypeId: candidate.contentTypeId,
        placeName: candidate.placeName,
        thumbnailUrl: candidate.thumbnailUrl,
        scheduledTime: candidate.suggestedTime,
        tags: candidate.matchedTags,
        crowdRate: candidate.crowdRate,
        visitDate,
        addr1: candidate.addr1,
        tel: candidate.tel,
        useFeeText: candidate.useFeeText,
        isFree: candidate.isFree,
        restDateText: candidate.restDateText,
        category: candidate.category,
        mapX: candidate.mapX,
        mapY: candidate.mapY,
      });
    }
    setItinerary(result);
    setShowAutoPlan(false);
  }

  async function fetchDocent(item, lang) {
    if (!item?.contentTypeId) {
      setDocentError('이 장소는 정보가 부족해 도슨트를 준비할 수 없어요.');
      return;
    }
    setDocentLoading(true);
    setDocentError(null);
    setDocentScript('');
    setDocentAudioUrl(null);
    try {
      const result = await api.getDocent(item.contentId, item.contentTypeId, lang);
      setDocentScript(result.scriptText);
      setDocentAudioUrl(result.audioUrl || null);
    } catch (e) {
      setDocentError(e.message);
    } finally {
      setDocentLoading(false);
    }
  }

  async function handleOpenDocent(item) {
    setDocentOpen(true);
    setDocentItem(item);
    setDocentPlaceName(item.placeName);
    setDocentLang('ko');
    setDocentScript('');
    setDocentAudioUrl(null);
    setDocentError(null);
    await fetchDocent(item, 'ko');
  }

  async function handleDocentLangChange(lang) {
    setDocentLang(lang);
    if (docentItem) await fetchDocent(docentItem, lang);
  }

  /** 동선 꼬임 자동 재배치 */
  async function handleOptimizeRoute() {
    if (!itineraryId || optimizeLoading) return;
    autoOptimizedRef.current = true;
    setOptimizeLoading(true);
    setAutoReplaceNotice(null);
    try {
      const result = await api.optimizeRoute(itineraryId, activeDate);
      setItinerary(result);
      setAutoReplaceNotice('동선이 꼬여 방문 순서를 자동으로 다시 잡았어요.');
      refreshTrigger();
    } catch (e) {
      setAutoReplaceNotice(`동선 재배치 실패: ${e.message}`);
    } finally {
      setOptimizeLoading(false);
      setTimeout(() => setAutoReplaceNotice(null), 6000);
    }
  }

  // 동선 꼬임 감지 시 한 번 자동 재계산
  useEffect(() => {
    if (!trigger?.routeTangleTrigger) {
      autoOptimizedRef.current = false;
      return;
    }
    if (autoOptimizedRef.current || optimizeLoading) return;
    handleOptimizeRoute();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trigger?.routeTangleTrigger]);

  async function handleShareItinerary() {
    if (!itineraryId || shareBusy) return;
    setShareBusy(true);
    try {
      const shared = await api.shareItinerary(itineraryId);
      const url = `${window.location.origin}${window.location.pathname}#/share/${shared.shareToken}`;
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(url);
        setAutoReplaceNotice('공유 링크를 복사했어요. 친구에게 보내 보세요!');
      } else {
        window.prompt('공유 링크를 복사하세요', url);
      }
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    } catch (e) {
      alert(e.message);
    } finally {
      setShareBusy(false);
    }
  }

  async function handleSubmitTripRecord({ overallRating, overallNote, visitFeedback }) {
    setTripSubmitting(true);
    try {
      await api.createTripRecord(sessionId, {
        itineraryId,
        overallRating,
        overallNote,
        rerouteCount,
        visitFeedback,
      });
      setTripRecordOpen(false);
      setItineraryId(null);
      setItinerary(null);
      setTrigger(null);
      setRecoResults(null);
      setRerouteCount(0);
    } catch (e) {
      alert(e.message);
    } finally {
      setTripSubmitting(false);
    }
  }

  if (shareToken) {
    return (
      <SharedItineraryScreen
        token={shareToken}
        onBack={() => {
          window.location.hash = '';
          setShareToken(null);
        }}
      />
    );
  }

  if (!itinerary) {
    return <CreateTripScreen onCreate={handleCreate} loading={creating} error={createError} />;
  }

  if (showSmartPlan) {
    return (
      <SmartPlanScreen
        key={smartPlanDate || 'all-days'}
        dayLabel={smartPlanDate ? dayLabelFor(smartPlanDate, tripDates) : null}
        onGenerate={handleGenerateSmartPlan}
        onConfirm={handleConfirmSmartPlan}
        onBrowseCategories={() => {
          setShowSmartPlan(false);
          setSmartPlanDate(null);
          setShowCategoryReco(true);
        }}
        onSkip={() => {
          setShowSmartPlan(false);
          setSmartPlanDate(null);
        }}
      />
    );
  }

  if (showCategoryReco) {
    return (
      <CategoryRecommendScreen
        regionCode={itinerary.signguFullCode}
        excludeContentIds={itinerary.items.map((i) => i.contentId).filter(Boolean)}
        onAdd={async (place) => {
          setAddingContentId(place.contentId);
          try {
            await addCandidateToItinerary(place);
          } finally {
            setAddingContentId(null);
          }
        }}
        addingId={addingContentId}
        onContinue={() => setShowCategoryReco(false)}
        onTryAi={() => {
          setShowCategoryReco(false);
          setShowAutoPlan(true);
        }}
      />
    );
  }

  if (showAutoPlan) {
    return (
      <AutoPlanScreen
        onGenerate={handleGenerateAutoPlan}
        onConfirm={handleConfirmAutoPlan}
        onSkip={() => setShowAutoPlan(false)}
      />
    );
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-inner">
          <span className="logo">🌬️ 바람따라</span>
          <div className="header-actions">
            <button className="btn-share" type="button" onClick={handleShareItinerary} disabled={shareBusy}>
              {shareBusy ? '링크 준비 중...' : '🔗 일정 공유'}
            </button>
            <button className="btn-finish" type="button" onClick={() => setTripRecordOpen(true)}>🏁 여행 마무리</button>
          </div>
        </div>
      </header>

      <main className="app-main">
        <PinwheelHero
          trigger={trigger}
          onRequestAlternatives={handleRequestAlternatives}
          loading={altLoading}
          onAutoReplace={handleAutoReplace}
          autoLoading={autoReplacing}
          onRerouteSchedule={handleRerouteSchedule}
          rerouteLoading={rerouteLoading}
          onOptimizeRoute={handleOptimizeRoute}
          optimizeLoading={optimizeLoading}
        />

        {autoReplaceNotice && <div className="auto-replace-notice">⚡ {autoReplaceNotice}</div>}

        <WeatherBanner items={weatherItems} />

        <FestivalBanner
          festivals={trigger?.festivalSuggestions}
          onAdd={handleAddFestival}
          addingId={addingFestivalId}
        />

        <DayTabs
          startDate={itinerary.startDate}
          endDate={itinerary.endDate}
          activeDate={activeDate}
          confirmedDates={itinerary.confirmedDates}
          itemCounts={itemCounts}
          onSelectDate={setActiveDate}
          onToggleConfirm={handleToggleConfirmDay}
          confirming={confirmingDay}
          onPlanDay={openSmartPlanForDate}
        />

        <ItineraryList
          items={visibleItems}
          affectedItemIds={trigger?.affectedItemIds}
          weatherAlert={Boolean(trigger?.weatherTrigger || trigger?.heatTrigger)}
          dayLabel={activeDayLabel}
          tripDates={tripDates}
          onUpdateTime={handleUpdateTime}
          onTogglePin={handleTogglePin}
          onDelete={handleDeleteItem}
          onOpenDocent={handleOpenDocent}
          onMoveDay={handleMoveDay}
          onPlanDay={() => openSmartPlanForDate(activeDate)}
        />

        <RecommendationSearch
          onSearch={handleSearch}
          onAdd={handleAddRecommendation}
          results={recoResults}
          loading={recoLoading}
          addingId={addingContentId}
        />
      </main>

      <footer className="app-footer">
        <p>바람따라 · 바람이 알려주는 실시간 여행</p>
      </footer>

      <AlternativesPanel
        open={altOpen}
        candidates={altCandidates}
        loading={altLoading}
        reason={altReason}
        onAdd={handleAddAlternative}
        addingId={addingContentId}
        onApplyAll={() => handleRerouteSchedule(trigger?.heatTrigger ? 'HEAT' : trigger?.weatherTrigger ? 'WEATHER' : undefined)}
        applyLoading={rerouteLoading}
        onClose={() => setAltOpen(false)}
      />

      <DocentModal
        open={docentOpen}
        placeName={docentPlaceName}
        script={docentScript}
        audioUrl={docentAudioUrl}
        loading={docentLoading}
        error={docentError}
        language={docentLang}
        onLanguageChange={handleDocentLangChange}
        onClose={() => setDocentOpen(false)}
      />

      <TripRecordModal
        open={tripRecordOpen}
        items={itinerary.items}
        submitting={tripSubmitting}
        onSubmit={handleSubmitTripRecord}
        onClose={() => setTripRecordOpen(false)}
      />
    </div>
  );
}
