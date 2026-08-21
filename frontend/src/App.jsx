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
import PinwheelLoader from './components/PinwheelLoader';
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
import AlertFeedScreen from './components/AlertFeedScreen';
import MyTripsScreen from './components/MyTripsScreen';
import TripRecordDetailScreen from './components/TripRecordDetailScreen';
import SettingsScreen from './components/SettingsScreen';
import GuideScreen from './components/GuideScreen';
import ExitConfirmModal from './components/ExitConfirmModal';
import { checkClosingGate } from './utils/closingTime';
import { checkTimeConflict } from './utils/timeConflict';
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

/**
 * 트리거 폴링용 현재 위치 - 실패/권한거부/미지원이면 조용히 null(이동시간 트리거만 생략되고
 * 나머지 트리거는 그대로 동작). 90초마다 도는 백그라운드 폴링이라 GPS를 매번 새로 켜지 않도록
 * maximumAge를 넉넉히 둔다(handleOptimizeFromGps의 수동 재계산과는 다른 용도).
 */
function getCurrentPositionSafe() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lon: pos.coords.longitude, lat: pos.coords.latitude }),
      () => resolve(null),
      { enableHighAccuracy: false, timeout: 5000, maximumAge: 2 * 60 * 1000 },
    );
  });
}

/** 고정(pin)한 장소 중 가장 뒤쪽(나중에 담긴) 항목 - 새 장소 추천의 근접 검색 기준점 */
function pinnedOriginItem(items) {
  const pinnedItems = (items || []).filter((i) => i.pinned);
  return pinnedItems.length > 0 ? pinnedItems[pinnedItems.length - 1] : null;
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
  const [creatingStage, setCreatingStage] = useState('');
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

  // 알림 탭으로 새 탭이 열린 경우 - sw.js가 붙여준 "?open={itineraryId}"를 읽어 그 일정으로 바로 진입
  useEffect(() => {
    const openId = new URLSearchParams(window.location.search).get('open');
    if (!openId) return;
    resumeDraftItinerary(openId);
    navigate('/trip');
    // 새로고침/재진입 시 같은 파라미터로 반복 리다이렉트되지 않도록 정리
    window.history.replaceState({}, '', window.location.pathname);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 알림 탭 시점에 이미 앱 탭이 열려 있던 경우 - sw.js의 notificationclick이 새 탭을 열지 않고
  // 기존 탭에 postMessage로 딥링크를 알려준다(위 쿼리 파라미터 effect와 동일한 목적지로 라우팅)
  useEffect(() => {
    function onMessage(event) {
      if (event.data?.type !== 'windtrail:notification-click') return;
      const openId = new URLSearchParams(new URL(event.data.url, window.location.origin).search).get('open');
      if (!openId) return;
      resumeDraftItinerary(openId);
      navigate('/trip');
    }
    navigator.serviceWorker?.addEventListener('message', onMessage);
    return () => navigator.serviceWorker?.removeEventListener('message', onMessage);
  }, [resumeDraftItinerary, navigate]);

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

  function handleOpenAlerts() {
    setMenuOpen(false);
    navigate('/alerts');
  }

  function handleOpenGuide() {
    setMenuOpen(false);
    navigate('/guide');
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
  const pinnedOrigin = itinerary ? pinnedOriginItem(itinerary.items) : null;

  // 슬롯별 예상 비용(1인 기준 × 인원수) 합산 - 정보없음 항목은 합계에서 빼고 별도로 안내한다(0원과 구분)
  const costSummary = visibleItems.reduce(
    (acc, item) => {
      if (item.estimatedCostPerPerson === null || item.estimatedCostPerPerson === undefined) {
        acc.unknownCount += 1;
      } else {
        acc.total += item.estimatedCostPerPerson * (itinerary?.partySize || 1);
        acc.hasKnown = true;
      }
      return acc;
    },
    { total: 0, unknownCount: 0, hasKnown: false },
  );

  const refreshTrigger = useCallback(async () => {
    if (!itineraryId) return;
    const origin = await getCurrentPositionSafe();
    api.getTriggerStatus(itineraryId, origin).then(setTrigger).catch(() => {});
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
    setCreatingStage('장소를 찾고 있어요...');
    try {
      // anchor/anchorTime은 첫 화면에서 미리 등록한 고정 일정(선택) - 일정 생성 API 자체엔 안 보냄
      const { anchor, anchorTime, ...tripFields } = formData;
      const created = await api.createItinerary(sessionId, {
        ...tripFields,
        endDate: tripFields.startDate, // 당일치기 고정
      });
      setItineraryId(created.itineraryId);
      setActiveDate(created.startDate);
      setDuplicateConflict(null);

      const finalItinerary = anchor
        ? await autoApplyAnchorPlan(created, anchor, anchorTime)
        : await autoApplySmartPlan(created);
      setItinerary(finalItinerary);
      setSmartPlanDate(null);
      navigate('/trip');
    } catch (e) {
      if (e.status === 409 && e.data) {
        setDuplicateConflict({ existing: e.data, formData });
      } else {
        setCreateError(e.message);
      }
    } finally {
      setCreating(false);
      setCreatingStage('');
    }
  }

  /**
   * 여행 생성 직후 - 리뷰 화면 없이 스마트 일정을 바로 만들어 담는다.
   * 생성된 itinerary 자체를 인자로 받아 처리한다(아직 리렌더 전이라 state의 itinerary/itineraryId는 못 씀).
   */
  async function autoApplySmartPlan(created) {
    let stops = [];
    try {
      setCreatingStage('오전·점심·오후·저녁 장소를 찾고 있어요...');
      const plan = await api.getSmartPlan(created.itineraryId, { date: created.startDate, standard: true });
      stops = plan?.stops || [];
    } catch {
      setAutoReplaceNotice('스마트 일정을 만들지 못했어요. 직접 담아보세요.');
      setTimeout(() => setAutoReplaceNotice(null), 6000);
      return created;
    }
    if (stops.length === 0) {
      return created;
    }
    for (let i = 0; i < stops.length; i++) {
      const stop = stops[i];
      setCreatingStage(`일정에 담고 있어요 (${i + 1}/${stops.length})...`);
      try {
        await api.addItem(created.itineraryId, {
          contentId: stop.contentId,
          contentTypeId: stop.contentTypeId,
          placeName: stop.placeName,
          thumbnailUrl: stop.thumbnailUrl,
          scheduledTime: stop.suggestedTime,
          tags: stop.matchedTags,
          crowdRate: stop.crowdRate,
          visitDate: stop.visitDate || created.startDate,
          addr1: stop.addr1,
          tel: stop.tel,
          useFeeText: stop.useFeeText,
          isFree: stop.isFree,
          estimatedCostPerPerson: stop.estimatedCostPerPerson,
          restDateText: stop.restDateText,
          closeTime: stop.closeTime,
          useTimeText: stop.useTimeText,
          homepageUrl: stop.homepageUrl,
          strollerFriendly: stop.strollerFriendly,
          accessibleFriendly: stop.accessibleFriendly,
          category: stop.category,
          mapX: stop.mapX,
          mapY: stop.mapY,
        });
      } catch {
        // 마감 임박 등으로 담기 실패한 곳은 건너뛰고 계속 - 이미 생성 단계에서 대부분 걸러짐
      }
    }
    let finalItinerary = await api.getItinerary(created.itineraryId);
    if (stops.length >= 2 && (finalItinerary.items || []).length >= 2) {
      setCreatingStage('이동 동선을 최적화하고 있어요...');
      finalItinerary = await api.optimizeRoute(created.itineraryId, created.startDate);
      setAutoReplaceNotice(finalItinerary.routeHint || '스마트 일정을 자동으로 담았어요 - 이동거리를 최소화한 순서예요.');
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
    return finalItinerary;
  }

  /**
   * 여행 생성 직후 - 첫 화면에서 미리 등록한 고정 일정(앵커)이 있으면 표준 4단계 대신 이 장소를
   * 기준으로 하루를 채운다(사용자가 이미 계획이 있으면 그 순서로 시작). autoApplySmartPlan과 동일하게
   * 리뷰 화면 없이 바로 담아 다음 화면을 시작한다.
   */
  async function autoApplyAnchorPlan(created, anchor, anchorTime) {
    let stops = [];
    try {
      setCreatingStage(`${anchor.placeName} 기준으로 장소를 찾고 있어요...`);
      stops = await api.getAnchorPlan(created.itineraryId, { anchor, anchorTime });
    } catch {
      setAutoReplaceNotice('고정 일정을 만들지 못했어요. 직접 담아보세요.');
      setTimeout(() => setAutoReplaceNotice(null), 6000);
      return created;
    }
    if (!stops || stops.length === 0) {
      return created;
    }
    for (let i = 0; i < stops.length; i++) {
      const stop = stops[i];
      setCreatingStage(`일정에 담고 있어요 (${i + 1}/${stops.length})...`);
      try {
        await api.addItem(created.itineraryId, {
          contentId: stop.contentId,
          contentTypeId: stop.contentTypeId,
          placeName: stop.placeName,
          thumbnailUrl: stop.thumbnailUrl,
          scheduledTime: stop.suggestedTime,
          tags: stop.matchedTags,
          crowdRate: stop.crowdRate,
          visitDate: stop.visitDate || created.startDate,
          addr1: stop.addr1,
          tel: stop.tel,
          useFeeText: stop.useFeeText,
          isFree: stop.isFree,
          estimatedCostPerPerson: stop.estimatedCostPerPerson,
          restDateText: stop.restDateText,
          closeTime: stop.closeTime,
          useTimeText: stop.useTimeText,
          homepageUrl: stop.homepageUrl,
          strollerFriendly: stop.strollerFriendly,
          accessibleFriendly: stop.accessibleFriendly,
          category: stop.category,
          mapX: stop.mapX,
          mapY: stop.mapY,
        });
      } catch {
        // 마감 임박 등으로 담기 실패한 곳은 건너뛰고 계속
      }
    }
    let finalItinerary = await api.getItinerary(created.itineraryId);
    if (stops.length >= 2 && (finalItinerary.items || []).length >= 2) {
      setCreatingStage('이동 동선을 최적화하고 있어요...');
      finalItinerary = await api.optimizeRoute(created.itineraryId, created.startDate);
      setAutoReplaceNotice(finalItinerary.routeHint || '등록한 고정 일정을 기준으로 하루 일정을 자동으로 담았어요.');
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
    return finalItinerary;
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

  /** 409(TimeSlotConflictException)면 "시간 겹침" 모달로 안내하고, 호출자가 실패를 알 수 있게 다시 던진다 */
  function reportIfTimeConflict(e, itemId) {
    if (e?.status === 409 && e?.data?.conflictingPlaceName) {
      const placeName = itinerary?.items?.find((i) => i.itemId === itemId)?.placeName;
      setClosingGate({ placeName, message: e.data.message, kind: 'CONFLICT' });
    }
  }

  async function handleUpdateTime(itemId, scheduledTime) {
    try {
      const result = await api.updateItem(itineraryId, itemId, { scheduledTime });
      setItinerary(result);
    } catch (e) {
      reportIfTimeConflict(e, itemId);
      throw e;
    }
  }

  async function handleUpdateItem(itemId, patch) {
    try {
      const result = await api.updateItem(itineraryId, itemId, patch);
      setItinerary(result);
      return result;
    } catch (e) {
      reportIfTimeConflict(e, itemId);
      throw e;
    }
  }

  async function handleTogglePin(itemId, isPinned) {
    const result = await api.updateItem(itineraryId, itemId, { isPinned });
    setItinerary(result);
  }

  async function handleDeleteItem(itemId) {
    const result = await api.deleteItem(itineraryId, itemId);
    if (result.autoReplacedPlaceName) {
      setAutoReplaceNotice(`"${result.autoReplacedPlaceName}"로 자동 채워드렸어요.`);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
    setItinerary(result);
  }

  async function handleSearch({ query, tags, maxBudgetPerPerson }) {
    setRecoLoading(true);
    try {
      const excludeContentIds = itinerary.items.map((i) => i.contentId).filter(Boolean);
      // 근처 우선 추천은 고정(pin)한 장소가 있을 때만 동작 - 고정 전에는 기준점 없이(거리 미반영) 검색
      const originItem = pinnedOriginItem(itinerary.items);
      const results = await api.getRecommendations({
        regionCode: itinerary.signguFullCode,
        withPet: itinerary.withPet,
        strollerFriendly: itinerary.strollerFriendly,
        accessibleFriendly: itinerary.accessibleFriendly,
        companionType: itinerary.companionType,
        adultAgeGroup: itinerary.adultAgeGroup,
        childAges: itinerary.childAges,
        query,
        tags,
        maxBudgetPerPerson,
        excludeContentIds,
        originContentId: originItem?.contentId,
        originContentTypeId: originItem?.contentTypeId,
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
    // 시간 겹침(다른 일정과의 충돌)을 마감시간 게이트보다 먼저·독립적으로 검사한다 - 안 그러면
    // "17시에 이미 다른 일정이 있어서" 못 넣는 경우에도 "이 장소 자체가 마감 임박"으로 잘못 안내됨.
    const explicitTime = candidate.scheduledTime || candidate.suggestedTime;
    if (explicitTime) {
      const conflict = checkTimeConflict(explicitTime, dayItems);
      if (conflict.blocked) {
        setClosingGate({ placeName: candidate.placeName, message: conflict.message, kind: 'CONFLICT' });
        throw new Error(conflict.message);
      }
    }
    const gate = checkClosingGate(candidate, { dayItems, visitDate: visitDate || itinerary?.startDate });
    if (gate.blocked) {
      setClosingGate({ placeName: candidate.placeName, message: gate.message, kind: 'CLOSING' });
      throw new Error(gate.message);
    }
    let result;
    try {
      result = await api.addItem(itineraryId, {
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
        estimatedCostPerPerson: candidate.estimatedCostPerPerson,
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
        backupContentId: candidate.backupContentId,
        backupContentTypeId: candidate.backupContentTypeId,
        backupPlaceName: candidate.backupPlaceName,
      });
    } catch (e) {
      // 클라이언트 사전검사를 통과했어도 서버가 그 사이 다른 항목 추가/수정으로 실제 겹침을
      // 판정한 경우(경쟁 상태) - 구조화된 409면 CONFLICT 문구로 안내.
      if (e?.status === 409 && e?.data?.conflictingPlaceName) {
        setClosingGate({ placeName: candidate.placeName, message: e.data.message, kind: 'CONFLICT' });
      }
      throw e;
    }
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
        // 원래 있던 시간대를 그대로 넘기다 보니(마감 임박 등으로) 1순위 후보가 그 시각엔 못
        // 들어갈 수 있다 - 실패하면 다음 후보로 계속 시도하고, 전부 실패했을 때만 안내한다.
        let result = null;
        let replacedWith = null;
        for (const candidate of candidates) {
          try {
            result = await api.addItem(itineraryId, {
              contentId: candidate.contentId,
              contentTypeId: candidate.contentTypeId,
              placeName: candidate.placeName,
              thumbnailUrl: candidate.thumbnailUrl,
              scheduledTime: affectedItem.scheduledTime,
              tags: candidate.matchedTags,
              crowdRate: candidate.crowdRate,
              // 교체 대상이었던 항목이 속했던 날짜를 그대로 유지
              visitDate: affectedItem.visitDate || itinerary.startDate,
              addr1: candidate.addr1,
              tel: candidate.tel,
              useFeeText: candidate.useFeeText,
              isFree: candidate.isFree,
              estimatedCostPerPerson: candidate.estimatedCostPerPerson,
              restDateText: candidate.restDateText,
              closeTime: candidate.closeTime,
              useTimeText: candidate.useTimeText,
              homepageUrl: candidate.homepageUrl,
              strollerFriendly: candidate.strollerFriendly,
              accessibleFriendly: candidate.accessibleFriendly,
              category: candidate.category,
              mapX: candidate.mapX,
              mapY: candidate.mapY,
              isAlternate: true,
            });
            replacedWith = candidate;
            break;
          } catch {
            // 이 후보는 그 시간대에 못 들어감(마감 임박 등) - 다음 후보로 계속
          }
        }
        if (!result) {
          setAutoReplaceNotice(`"${affectedItem.placeName}" 자리에 넣을 수 있는 대안을 찾지 못했어요.`);
          return;
        }
        setItinerary(result);
        setAutoReplaceNotice(`"${affectedItem.placeName}"을(를) "${replacedWith.placeName}"(으)로 자동 교체했어요.${rainNote}`);
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
          estimatedCostPerPerson: next.estimatedCostPerPerson,
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
    let added = 0;
    const visitDate = activeDate || itinerary.startDate;
    for (const candidate of selected) {
      try {
        await addCandidateToItinerary(
          { ...candidate, scheduledTime: candidate.suggestedTime },
          visitDate,
          false,
        );
        added += 1;
      } catch {
        skipped += 1;
      }
    }
    let result = itinerary;
    try {
      result = await api.getItinerary(itineraryId);
      setItinerary(result);
    } catch {
      /* keep */
    }
    // 스마트플랜 확정 때(confirmSmartPlanCore)와 동일하게 커밋 직후 실제 이동시간 기준으로 동선을 다시 잡는다
    if (added >= 2 && (result?.items || []).length >= 2) {
      try {
        result = await api.optimizeRoute(itineraryId, visitDate);
        setItinerary(result);
      } catch {
        /* keep unoptimized order */
      }
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

  /** 동선 재계산 — GPS 있으면 시작점, 없으면 장소만으로 매트릭스 TSP + 시간표.
   *  startTime("HH:mm")을 주면 첫 장소 시각을 사용자가 지정한 그대로 고정한다. */
  async function handleOptimizeRoute(origin, startTime) {
    if (!itineraryId || optimizeLoading) return;
    autoOptimizedRef.current = true;
    setOptimizeLoading(true);
    setAutoReplaceNotice(null);
    try {
      const result = await api.optimizeRoute(itineraryId, activeDate, origin, startTime);
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

  /** 오늘 동선 「동선 재계산」 — GPS 시도 후 서버 TSP·시간표.
   *  startTime을 지정했으면 GPS 위치와 무관하게 그 시각을 첫 장소 도착 시각으로 고정한다. */
  function handleOptimizeFromGps(startTime) {
    setOptimizeLoading(true);
    setAutoReplaceNotice('동선 재계산 중…');
    if (!navigator.geolocation) {
      handleOptimizeRoute(null, startTime);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        handleOptimizeRoute({
          lon: pos.coords.longitude,
          lat: pos.coords.latitude,
        }, startTime);
      },
      () => {
        // 위치 거부·실패여도 장소 간 매트릭스로 재계산
        handleOptimizeRoute(null, startTime);
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
      // navigate('/')를 직접 부르지 않는다 - /trip 라우트 가드(!itinerary → <Navigate to="/"/>)가
      // itinerary=null이 되는 순간 자연스럽게 처리한다. 여기서 navigate까지 같이 부르면, 모달이
      // 방금 닫히며 useModalHistory 클린업이 실행하는 history.back()과 겹쳐 방금 이동한 "/"에서
      // 다시 "/trip"(빈 화면)으로 되돌아가버리는 경합이 있었음(2026-08-16 사용자 제보 - 여행 마무리
      // 후 첫 화면으로 안 넘어가고 빈 화면만 남음).
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
        onNavigateAlerts={handleOpenAlerts}
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
                  loadingStage={creatingStage}
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
                childAges={itinerary.childAges}
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
              {/* CreateTripScreen의 로딩 오버레이는 라우트가 "/trip"으로 바뀌는 순간 함께
                  언마운트된다 - 그 사이 담긴 장소가 아직 반영 안 된 빈 화면이 잠깐 보이는 걸
                  막기 위해 creating이 꺼질 때까지 이 라우트에서도 같은 오버레이를 이어서 띄운다. */}
              {creating && (
                <PinwheelLoader message={creatingStage || '지금 일정을 스마트하게 고르고 있어요...'} />
              )}
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
                  {visibleItems.length > 0 && (
                    <span className="daytrip-cost" title={costSummary.unknownCount > 0 ? `정보없음 ${costSummary.unknownCount}곳 제외` : undefined}>
                      💰 Σ {costSummary.total.toLocaleString()}원
                      {costSummary.unknownCount > 0 && ` (정보없음 ${costSummary.unknownCount}곳 제외)`}
                    </span>
                  )}
                  <span className="daytrip-count">{visibleItems.length}곳</span>
                </div>

                <ItineraryList
                  items={visibleItems}
                  affectedItemIds={trigger?.affectedItemIds}
                  weatherAffectedItemIds={trigger?.weatherAffectedItemIds}
                  businessAffectedItemIds={trigger?.businessAffectedItemIds}
                  closedDayAffectedItemIds={trigger?.closedDayAffectedItemIds}
                  hoursEndedAffectedItemIds={trigger?.hoursEndedAffectedItemIds}
                  crowdAffectedItemIds={trigger?.crowdAffectedItemIds}
                  weatherAlert={Boolean(trigger?.weatherTrigger || trigger?.heatTrigger)}
                  trigger={trigger}
                  dayLabel="오늘"
                  onUpdateTime={handleUpdateTime}
                  onUpdateItem={handleUpdateItem}
                  onTogglePin={handleTogglePin}
                  onDelete={handleDeleteItem}
                  onOpenDocent={handleOpenDocent}
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
                  pinnedPlaceName={pinnedOrigin?.placeName}
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
                kind={closingGate?.kind}
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
        path="/alerts"
        element={
          !itinerary ? <Navigate to="/" replace /> : (
            <>
              <BackHeader title="알림" onMenuClick={() => setMenuOpen(true)} />
              <AlertFeedScreen itineraryId={itineraryId} />
            </>
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
        path="/trip-records/:tripRecordId"
        element={
          <>
            <BackHeader title="여행 기록" onMenuClick={() => setMenuOpen(true)} />
            <TripRecordDetailScreen sessionId={sessionId} onViewItinerary={handleResumeDraft} />
          </>
        }
      />
      <Route
        path="/settings"
        element={
          <>
            <BackHeader title="설정" onMenuClick={() => setMenuOpen(true)} />
            <SettingsScreen sessionId={sessionId} />
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
