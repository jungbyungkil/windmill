import { useState, useEffect, useCallback, useRef } from 'react';
import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import useSession from './hooks/useSession';
import useExitConfirm from './hooks/useExitConfirm';
import * as api from './api/windmillApi';
import CreateTripScreen from './components/CreateTripScreen';
import SmartPlanScreen from './components/SmartPlanScreen';
import CategoryRecommendScreen from './components/CategoryRecommendScreen';
import AutoPlanScreen from './components/AutoPlanScreen';
import BackHeader from './components/BackHeader';
import PinwheelHero from './components/PinwheelHero';
import WeatherBanner from './components/WeatherBanner';
import MidWeatherBanner from './components/MidWeatherBanner';
import FestivalBanner from './components/FestivalBanner';
import ItineraryList from './components/ItineraryList';
import RecommendationSearch from './components/RecommendationSearch';
import AlternativesPanel from './components/AlternativesPanel';
import DocentModal from './components/DocentModal';
import TripRecordModal from './components/TripRecordModal';
import SharedItineraryScreen from './components/SharedItineraryScreen';
import ClosingGateModal from './components/ClosingGateModal';
import DuplicateItineraryModal from './components/DuplicateItineraryModal';
import GlobalMenu from './components/GlobalMenu';
import MyTripsScreen from './components/MyTripsScreen';
import HistoryScreen from './components/HistoryScreen';
import SettingsScreen from './components/SettingsScreen';
import GuideScreen from './components/GuideScreen';
import ExitConfirmModal from './components/ExitConfirmModal';
import { checkClosingGate } from './utils/closingTime';
import { recordView } from './utils/viewHistory';
import './App.css';

const TRIGGER_POLL_MS = 90 * 1000;

function readShareTokenFromHash() {
  const m = window.location.hash.match(/^#\/share\/([A-Za-z0-9_-]+)/);
  return m ? m[1] : null;
}

function formatTripDate(dateStr) {
  if (!dateStr) return '';
  const d = new Date(dateStr + 'T00:00:00');
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][d.getDay()];
  return `${d.getMonth() + 1}/${d.getDate()} (${weekday})`;
}

/** "09:00" → 분. 없거나 잘못되면 null */
function scheduleMinutes(scheduledTime) {
  if (!scheduledTime || typeof scheduledTime !== 'string') return null;
  const parts = scheduledTime.trim().split(':');
  if (parts.length < 2) return null;
  const h = Number(parts[0]);
  const m = Number(parts[1]);
  if (!Number.isFinite(h) || !Number.isFinite(m) || h < 0 || h > 23 || m < 0 || m > 59) return null;
  return h * 60 + m;
}

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const { sessionId, itineraryId, setItineraryId, draftItineraryId, leaveItineraryView, resumeDraftItinerary } = useSession();

  const [shareToken, setShareToken] = useState(() => readShareTokenFromHash());
  const [itinerary, setItinerary] = useState(null);
  const [creating, setCreating] = useState(false);
  const [startingStoryId, setStartingStoryId] = useState(null);
  const [createError, setCreateError] = useState(null);
  const [duplicateConflict, setDuplicateConflict] = useState(null);
  const [overwritingDuplicate, setOverwritingDuplicate] = useState(false);
  /** 핵심: 혼잡↓·동선최적화 스마트 일정 우선 노출 */
  const [smartPlanDate, setSmartPlanDate] = useState(null);

  const [trigger, setTrigger] = useState(null);
  const [weatherItems, setWeatherItems] = useState(null);
  const [midWeather, setMidWeather] = useState(null);

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
  const [closingGate, setClosingGate] = useState(null);
  const [rerouteLoading, setRerouteLoading] = useState(false);
  const [optimizeLoading, setOptimizeLoading] = useState(false);
  const [sortByTimeLoading, setSortByTimeLoading] = useState(false);
  const [shareBusy, setShareBusy] = useState(false);
  const autoOptimizedRef = useRef(false);

  const [activeDate, setActiveDate] = useState(null);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    function onHash() {
      setShareToken(readShareTokenFromHash());
    }
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // 사용자가 일정을 연 경우에만 로드. 새로고침/재방문 시 메인 대시보드를 유지한다.
  useEffect(() => {
    if (!itineraryId) {
      setItinerary(null);
      setTrigger(null);
      setWeatherItems(null);
      setMidWeather(null);
      setActiveDate(null);
      return;
    }
    api.getItinerary(itineraryId)
      .then(setItinerary)
      .catch(() => setItineraryId(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itineraryId]);

  // itineraryId는 설정됐지만(초안 재개 등) 아직 itinerary를 못 불러온 사이 - 이 동안엔 라우트 가드가
  // "/"로 튕겼다가 로드 완료 후 다시 "/trip"으로 튕기는 깜빡임을 피하기 위한 로딩 상태
  const restoring = itineraryId != null && !itinerary;

  // 뒤로가기 종료 확인 - 최상위(홈, CreateTripScreen이 실제로 보이는 시점)에서만 적용
  const isTopLevel = location.pathname === '/' && !itinerary;
  const exitConfirm = useExitConfirm(isTopLevel && !shareToken && !restoring);

  function handleGoHome() {
    leaveItineraryView();
    navigate('/');
  }

  function handleResumeDraft(id) {
    resumeDraftItinerary(id);
    navigate('/trip');
  }

  function handleOpenMyTrips() {
    setMenuOpen(false);
    navigate('/my-trips');
  }

  function handleOpenHistory() {
    setMenuOpen(false);
    navigate('/history');
  }

  function handleOpenSettings() {
    setMenuOpen(false);
    navigate('/settings');
  }

  function handleOpenGuide() {
    setMenuOpen(false);
    navigate('/guide');
  }

  function handleMenuNewTrip() {
    setMenuOpen(false);
    handleGoHome();
  }

  // 일정이 새로 로드되면 여행 시작일을 기본 활성 날짜로
  useEffect(() => {
    if (itinerary?.startDate && !activeDate) {
      setActiveDate(itinerary.startDate);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itinerary?.startDate]);

  // 당일치기: 해당 날짜 일정만 표시
  const tripDate = itinerary?.startDate || null;
  const visibleItems = itinerary
    ? [...itinerary.items]
        .filter((i) => (i.visitDate || itinerary.startDate) === (activeDate || tripDate))
        .sort((a, b) => {
          const ta = scheduleMinutes(a.scheduledTime);
          const tb = scheduleMinutes(b.scheduledTime);
          if (ta !== tb) {
            if (ta == null) return 1;
            if (tb == null) return -1;
            return ta - tb;
          }
          return (a.displayOrder ?? 0) - (b.displayOrder ?? 0);
        })
    : [];
  function openSmartPlanForDate(date) {
    setSmartPlanDate(date || itinerary?.startDate || null);
    navigate('/smart-plan');
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
    if (itinerary?.signguFullCode) {
      api.getMidWeather(itinerary.signguFullCode).then(setMidWeather).catch(() => setMidWeather(null));
    }
    const id = setInterval(refreshTrigger, TRIGGER_POLL_MS);
    return () => clearInterval(id);
  }, [itineraryId, itinerary?.weatherNx, itinerary?.weatherNy, itinerary?.signguFullCode, refreshTrigger]);

  async function handleCreate(formData) {
    setCreating(true);
    setCreateError(null);
    try {
      const result = await api.createItinerary(sessionId, {
        ...formData,
        endDate: formData.startDate, // 당일치기 고정
      });
      setItinerary(result);
      setItineraryId(result.itineraryId);
      setActiveDate(result.startDate);
      setSmartPlanDate(result.startDate);
      setDuplicateConflict(null);
      navigate('/smart-plan');
    } catch (e) {
      if (e.status === 409 && e.data) {
        setDuplicateConflict({ existing: e.data, formData });
      } else {
        setCreateError(e.message);
      }
    } finally {
      setCreating(false);
    }
  }

  /** 중복 안내 모달 - "기존 일정 수정" 선택 시 그 일정으로 이동해 편집 */
  function handleEditExistingItinerary() {
    const id = duplicateConflict?.existing?.itineraryId;
    setDuplicateConflict(null);
    if (id != null) {
      resumeDraftItinerary(id);
      navigate('/trip');
    }
  }

  /** 중복 안내 모달 - "새로 만들기" 선택 시 기존 일정을 지우고 같은 입력값으로 재생성 */
  async function handleOverwriteDuplicate() {
    const formData = duplicateConflict?.formData;
    if (!formData) return;
    setOverwritingDuplicate(true);
    try {
      await handleCreate({ ...formData, force: true });
    } finally {
      setOverwritingDuplicate(false);
    }
  }

  /** 추천 기록 카드 → 해당 장소·시간 그대로 복제해 일정 화면으로 바로 진입 */
  async function handleStartFromStory(story, startDate) {
    if (!story?.id || !startDate) return;
    setStartingStoryId(story.id);
    setCreateError(null);
    try {
      const result = await api.startFromTripRecord(sessionId, story.id, { startDate });
      setItinerary(result);
      setItineraryId(result.itineraryId);
      setActiveDate(result.startDate);
      setSmartPlanDate(null);
      navigate('/trip');
    } catch (e) {
      setCreateError(e.message);
    } finally {
      setStartingStoryId(null);
    }
  }

  async function handleUpdateTime(itemId, scheduledTime) {
    const result = await api.updateItem(itineraryId, itemId, { scheduledTime });
    setItinerary(result);
  }

  async function handleUpdateItem(itemId, patch) {
    const result = await api.updateItem(itineraryId, itemId, patch);
    setItinerary(result);
    return result;
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
    const dayItems = (itinerary?.items || []).filter(
      (i) => (i.visitDate || itinerary.startDate) === (visitDate || itinerary?.startDate),
    );
    const gate = checkClosingGate(candidate, { dayItems, visitDate: visitDate || itinerary?.startDate });
    if (gate.blocked) {
      setClosingGate({ placeName: candidate.placeName, message: gate.message });
      throw new Error(gate.message);
    }
    const result = await api.addItem(itineraryId, {
      contentId: candidate.contentId,
      contentTypeId: candidate.contentTypeId,
      placeName: candidate.placeName,
      thumbnailUrl: candidate.thumbnailUrl,
      scheduledTime: candidate.scheduledTime || candidate.suggestedTime,
      tags: candidate.matchedTags,
      crowdRate: candidate.crowdRate,
      visitDate,
      addr1: candidate.addr1,
      tel: candidate.tel,
      useFeeText: candidate.useFeeText,
      isFree: candidate.isFree,
      restDateText: candidate.restDateText,
      closeTime: candidate.closeTime,
      useTimeText: candidate.useTimeText,
      homepageUrl: candidate.homepageUrl,
      strollerFriendly: candidate.strollerFriendly,
      accessibleFriendly: candidate.accessibleFriendly,
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
    } catch {
      /* 마감 게이트 등 — ClosingGateModal / 서버 메시지로 안내 */
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
      const affectedId = (trigger?.weatherAffectedItemIds?.[0]
        ?? trigger?.affectedItemIds?.[0]);
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
          closeTime: top.closeTime,
          useTimeText: top.useTimeText,
          homepageUrl: top.homepageUrl,
          strollerFriendly: top.strollerFriendly,
          accessibleFriendly: top.accessibleFriendly,
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

      const affectedIds = (trigger?.weatherAffectedItemIds?.length
        ? trigger.weatherAffectedItemIds
        : (trigger?.affectedItemIds || [])).map(Number);
      const affectedItems = itinerary.items.filter((i) => affectedIds.includes(Number(i.itemId)));
      const targets = affectedItems.length > 0
        ? affectedItems
        : itinerary.items.filter((i) => {
            if ((i.visitDate || itinerary.startDate) !== activeDate) return false;
            const tags = i.tags || [];
            if (tags.includes('#실내') || tags.includes('#맛집')) return false;
            return true;
          });

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
          homepageUrl: next.homepageUrl,
          strollerFriendly: next.strollerFriendly,
          accessibleFriendly: next.accessibleFriendly,
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
    const date = smartPlanDate || itinerary?.startDate;
    return api.getSmartPlan(itineraryId, {
      placeCount: 5,
      date,
    });
  }

  /** 스마트 일정 확정 공통 로직 - 목적지(대시보드/카테고리 추천)는 호출자가 navigate()로 결정 */
  async function confirmSmartPlanCore(selected) {
    let result = itinerary;
    const fallbackDate = smartPlanDate || activeDate || itinerary.startDate;
    for (const candidate of selected) {
      try {
        result = await addCandidateToItinerary(
          { ...candidate, scheduledTime: candidate.suggestedTime },
          candidate.visitDate || fallbackDate,
          false,
        );
      } catch (e) {
        // 마감 게이트면 모달 후 해당 장소만 건너뜀
        if (!closingGate && e?.message) {
          setClosingGate({ placeName: candidate.placeName, message: e.message });
        }
      }
    }
    // refresh from last successful setItinerary inside addCandidate
    result = itinerary;
    // re-fetch to sync if partial adds
    try {
      result = await api.getItinerary(itineraryId);
      setItinerary(result);
    } catch {
      /* keep local */
    }
    const visitDate = selected[0]?.visitDate || fallbackDate;
    if (selected.length >= 2 && (result?.items || []).length >= 2) {
      result = await api.optimizeRoute(itineraryId, visitDate);
      setAutoReplaceNotice(
        result.routeHint
          || '이 순서가 총 이동거리를 최소화한 순서예요.',
      );
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
    setItinerary(result);
    setSmartPlanDate(null);
    if (result?.startDate) setActiveDate(result.startDate);
  }

  async function handleConfirmSmartPlan(selected) {
    await confirmSmartPlanCore(selected);
    navigate('/trip');
  }

  /** 스마트 동선에서 고른 장소를 먼저 담은 뒤 카테고리 추천으로 이동 */
  async function handleBrowseCategoriesFromSmartPlan(selected = []) {
    if (selected.length > 0) {
      await confirmSmartPlanCore(selected);
    } else {
      setSmartPlanDate(null);
    }
    navigate('/category');
  }

  function handleGenerateAutoPlan(tags) {
    return api.getAutoPlan(itineraryId, { tags, placeCount: 5 });
  }

  async function handleConfirmAutoPlan(selected) {
    let skipped = 0;
    const visitDate = activeDate || itinerary.startDate;
    for (const candidate of selected) {
      try {
        await addCandidateToItinerary(
          { ...candidate, scheduledTime: candidate.suggestedTime },
          visitDate,
          false,
        );
      } catch {
        skipped += 1;
      }
    }
    try {
      const result = await api.getItinerary(itineraryId);
      setItinerary(result);
    } catch {
      /* keep */
    }
    if (skipped > 0) {
      setAutoReplaceNotice(`마감 시간 때문에 ${skipped}곳은 자동으로 건너뛰었어요.`);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
    navigate('/trip');
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
    recordView({ type: 'place', id: item.contentId, name: item.placeName, thumbnail: item.thumbnailUrl });
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

  /** 동선 재계산 — GPS 있으면 시작점, 없으면 장소만으로 매트릭스 TSP + 시간표 */
  async function handleOptimizeRoute(origin) {
    if (!itineraryId || optimizeLoading) return;
    autoOptimizedRef.current = true;
    setOptimizeLoading(true);
    setAutoReplaceNotice(null);
    try {
      const result = await api.optimizeRoute(itineraryId, activeDate, origin);
      setItinerary(result);
      setAutoReplaceNotice(
        result.routeHint
          || (origin
            ? '현재 위치를 반영해 동선을 다시 계산했어요.'
            : '이동시간·체류를 반영해 동선을 다시 계산했어요.'),
      );
      refreshTrigger();
    } catch (e) {
      setAutoReplaceNotice(`동선 재계산 실패: ${e.message}`);
    } finally {
      setOptimizeLoading(false);
      setTimeout(() => setAutoReplaceNotice(null), 6000);
    }
  }

  /** 오늘 동선 「동선 재계산」 — GPS 시도 후 서버 TSP·시간표 */
  function handleOptimizeFromGps() {
    setOptimizeLoading(true);
    setAutoReplaceNotice('동선 재계산 중…');
    if (!navigator.geolocation) {
      handleOptimizeRoute(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        handleOptimizeRoute({
          lon: pos.coords.longitude,
          lat: pos.coords.latitude,
        });
      },
      () => {
        // 위치 거부·실패여도 장소 간 매트릭스로 재계산
        handleOptimizeRoute(null);
      },
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 },
    );
  }

  /** 오늘 일정을 방문 시각 순으로 재정렬 (시각은 유지) */
  async function handleSortByTime() {
    if (!itineraryId || sortByTimeLoading) return;
    setSortByTimeLoading(true);
    setAutoReplaceNotice(null);
    try {
      const result = await api.sortItineraryByTime(itineraryId, activeDate);
      setItinerary(result);
      setAutoReplaceNotice('오늘 일정을 시간 순서로 정렬했어요.');
    } catch (e) {
      setAutoReplaceNotice(`시간순 정렬 실패: ${e.message}`);
    } finally {
      setSortByTimeLoading(false);
      setTimeout(() => setAutoReplaceNotice(null), 4000);
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
      navigate('/');
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

  if (restoring) {
    return <div className="app-loading">🌬️ 불러오는 중...</div>;
  }

  return (
    <>
      <GlobalMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        onNavigateMyTrips={handleOpenMyTrips}
        onNavigateHistory={handleOpenHistory}
        onNavigateHome={handleMenuNewTrip}
        onNavigateSettings={handleOpenSettings}
        onNavigateGuide={handleOpenGuide}
      />
      <ExitConfirmModal
        open={exitConfirm.confirmOpen}
        onCancel={exitConfirm.cancel}
        onConfirm={exitConfirm.confirmExit}
      />
      <Routes>
      <Route
        path="/"
        element={
          itinerary
            ? <Navigate to="/trip" replace />
            : (
              <>
                <BackHeader showBack={false} onMenuClick={() => setMenuOpen(true)} />
                <CreateTripScreen
                  sessionId={sessionId}
                  onCreate={handleCreate}
                  onStartFromStory={handleStartFromStory}
                  loading={creating}
                  startingStoryId={startingStoryId}
                  error={createError}
                  draftItineraryId={draftItineraryId}
                  onResumeDraft={handleResumeDraft}
                />
                <DuplicateItineraryModal
                  open={Boolean(duplicateConflict)}
                  existing={duplicateConflict?.existing}
                  dateLabel={formatTripDate(duplicateConflict?.existing?.startDate)}
                  overwriting={overwritingDuplicate}
                  onEditExisting={handleEditExistingItinerary}
                  onOverwrite={handleOverwriteDuplicate}
                  onClose={() => setDuplicateConflict(null)}
                />
              </>
            )
        }
      />
      <Route
        path="/smart-plan"
        element={
          !itinerary ? <Navigate to="/" replace /> : (
            <>
              <BackHeader title="스마트 일정" onMenuClick={() => setMenuOpen(true)} />
              <SmartPlanScreen
                key={smartPlanDate || itinerary.startDate || 'day-trip'}
                dayLabel={null}
                onGenerate={handleGenerateSmartPlan}
                onConfirm={handleConfirmSmartPlan}
                onBrowseCategories={handleBrowseCategoriesFromSmartPlan}
                onSkip={() => {
                  setSmartPlanDate(null);
                  navigate('/trip');
                }}
              />
            </>
          )
        }
      />
      <Route
        path="/category"
        element={
          !itinerary ? <Navigate to="/" replace /> : (
            <>
              <BackHeader title="카테고리 추천" onMenuClick={() => setMenuOpen(true)} />
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
                onContinue={() => navigate('/trip')}
                onTryAi={() => navigate('/auto-plan')}
              />
            </>
          )
        }
      />
      <Route
        path="/auto-plan"
        element={
          !itinerary ? <Navigate to="/" replace /> : (
            <>
              <BackHeader title="AI 일정 짜기" onMenuClick={() => setMenuOpen(true)} />
              <AutoPlanScreen
                onGenerate={handleGenerateAutoPlan}
                onConfirm={handleConfirmAutoPlan}
                onSkip={() => navigate('/trip')}
              />
            </>
          )
        }
      />
      <Route
        path="/trip"
        element={
          !itinerary ? <Navigate to="/" replace /> : (
            <div className="app">
              <BackHeader title="바람따라" onMenuClick={() => setMenuOpen(true)} />
              <header className="app-header">
                <div className="header-inner">
                  <button type="button" className="logo logo-btn" onClick={handleGoHome} title="메인으로">
                    🌬️ 바람따라
                  </button>
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
                  onOptimizeRoute={() => handleOptimizeRoute()}
                  optimizeLoading={optimizeLoading}
                />

                {autoReplaceNotice && <div className="auto-replace-notice">⚡ {autoReplaceNotice}</div>}

                <WeatherBanner items={weatherItems} />

                <div className="daytrip-chip-row">
                  <span className="daytrip-chip">당일치기</span>
                  {tripDate && <span className="daytrip-date">{formatTripDate(tripDate)}</span>}
                  <span className="daytrip-count">{visibleItems.length}곳</span>
                </div>

                <ItineraryList
                  itineraryId={itineraryId}
                  items={visibleItems}
                  affectedItemIds={trigger?.affectedItemIds}
                  weatherAffectedItemIds={trigger?.weatherAffectedItemIds}
                  businessAffectedItemIds={trigger?.businessAffectedItemIds}
                  crowdAffectedItemIds={trigger?.crowdAffectedItemIds}
                  weatherAlert={Boolean(trigger?.weatherTrigger || trigger?.heatTrigger)}
                  trigger={trigger}
                  dayLabel="오늘"
                  onUpdateTime={handleUpdateTime}
                  onUpdateItem={handleUpdateItem}
                  onTogglePin={handleTogglePin}
                  onDelete={handleDeleteItem}
                  onOpenDocent={handleOpenDocent}
                  onPlanDay={() => openSmartPlanForDate(tripDate)}
                  onSortByTime={handleSortByTime}
                  sortByTimeLoading={sortByTimeLoading}
                  onOptimizeFromGps={handleOptimizeFromGps}
                  gpsOptimizing={optimizeLoading}
                />

                <RecommendationSearch
                  onSearch={handleSearch}
                  onAdd={handleAddRecommendation}
                  results={recoResults}
                  loading={recoLoading}
                  addingId={addingContentId}
                />

                <FestivalBanner
                  festivals={trigger?.festivalSuggestions}
                  onAdd={handleAddFestival}
                  addingId={addingFestivalId}
                />

                <MidWeatherBanner forecast={midWeather} />
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

              <ClosingGateModal
                open={Boolean(closingGate)}
                placeName={closingGate?.placeName}
                message={closingGate?.message}
                onClose={() => setClosingGate(null)}
              />

              <TripRecordModal
                open={tripRecordOpen}
                items={itinerary.items}
                submitting={tripSubmitting}
                onSubmit={handleSubmitTripRecord}
                onClose={() => setTripRecordOpen(false)}
              />
            </div>
          )
        }
      />
      <Route
        path="/my-trips"
        element={
          <>
            <BackHeader title="내 여행 관리" onMenuClick={() => setMenuOpen(true)} />
            <MyTripsScreen sessionId={sessionId} onResume={handleResumeDraft} />
          </>
        }
      />
      <Route
        path="/history"
        element={
          <>
            <BackHeader title="이용 히스토리" onMenuClick={() => setMenuOpen(true)} />
            <HistoryScreen />
          </>
        }
      />
      <Route
        path="/settings"
        element={
          <>
            <BackHeader title="설정" onMenuClick={() => setMenuOpen(true)} />
            <SettingsScreen />
          </>
        }
      />
      <Route
        path="/guide"
        element={
          <>
            <BackHeader title="이용 가이드" onMenuClick={() => setMenuOpen(true)} />
            <GuideScreen />
          </>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
