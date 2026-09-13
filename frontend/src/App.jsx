import { useState, useEffect, useCallback, useRef } from 'react';
import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import useSession from './hooks/useSession';
import * as api from './api/windmillApi';
import CreateTripScreen from './components/CreateTripScreen';
import SmartPlanScreen from './components/SmartPlanScreen';
import CategoryRecommendScreen from './components/CategoryRecommendScreen';
import AutoPlanScreen from './components/AutoPlanScreen';
import BackHeader from './components/BackHeader';
import PinwheelHero from './components/PinwheelHero';
import PinwheelLoader from './components/PinwheelLoader';
import Toast from './components/Toast';
import VisitConfirmationNudge from './components/VisitConfirmationNudge';
import WeatherBanner from './components/WeatherBanner';
import MidWeatherBanner from './components/MidWeatherBanner';
import FestivalBanner from './components/FestivalBanner';
import ItineraryList from './components/ItineraryList';
import DayRouteMap from './components/DayRouteMap';
import RecommendationSearch from './components/RecommendationSearch';
import BottomTabBar from './components/BottomTabBar';
import AlternativesPanel from './components/AlternativesPanel';
import DocentModal from './components/DocentModal';
import TripRecordModal from './components/TripRecordModal';
import SharedItineraryScreen from './components/SharedItineraryScreen';
import ClosingGateModal from './components/ClosingGateModal';
import HoursWarningModal from './components/HoursWarningModal';
import SuggestRouteCompare from './components/SuggestRouteCompare';
import DuplicateItineraryModal from './components/DuplicateItineraryModal';
import PlanHistoryPanel from './components/PlanHistoryPanel';
import GlobalMenu from './components/GlobalMenu';
import AlertFeedScreen from './components/AlertFeedScreen';
import MyTripsScreen from './components/MyTripsScreen';
import TripRecordDetailScreen from './components/TripRecordDetailScreen';
import SettingsScreen from './components/SettingsScreen';
import GuideScreen from './components/GuideScreen';
import TravelerProfileScreen from './components/TravelerProfileScreen';
import { recordView } from './utils/viewHistory';
import { placeSnapshotFields } from './utils/placeSnapshot';
import { syncPushSubscription } from './utils/webPush';
import { useResolveFeedback } from './hooks/useResolveFeedback';
import './App.css';

const TRIGGER_POLL_MS = 90 * 1000;
const TRIP_SECTIONS = ['home', 'map', 'search', 'alerts', 'profile'];

function tripSectionPath(key) {
  return key === 'home' ? '/trip' : `/trip#${key}`;
}

function tripSectionFromHash(hash) {
  const fromHash = (hash || '').replace('#', '');
  if (!fromHash || fromHash === 'home') return 'home';
  return TRIP_SECTIONS.includes(fromHash) ? fromHash : 'home';
}

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

function isTripToday(dateStr) {
  if (!dateStr) return false;
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return dateStr === `${y}-${m}-${d}`;
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

/**
 * 방문 예정 시각이 지났는데 아직 완료 처리 안 된 첫 항목 - "OO 다녀오셨나요?" nudge 대상(방문 완료
 * UX 개선 스펙 2항). items는 이미 시각순으로 정렬된 visibleItems를 받는다는 전제.
 */
function overdueUnconfirmedItem(items, nowMin) {
  if (!Array.isArray(items)) return null;
  for (const item of items) {
    if (item.completed) continue;
    const t = scheduleMinutes(item.scheduledTime);
    if (t != null && t <= nowMin) return item;
  }
  return null;
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
  const triggerRef = useRef(null);
  useEffect(() => { triggerRef.current = trigger; }, [trigger]);
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
  const [altError, setAltError] = useState(null);

  const [historyOpen, setHistoryOpen] = useState(false);
  const [reverting, setReverting] = useState(false);

  const [docentOpen, setDocentOpen] = useState(false);
  const [docentItem, setDocentItem] = useState(null);
  const [docentPlaceName, setDocentPlaceName] = useState('');
  const [docentScript, setDocentScript] = useState('');
  const [docentAudioUrl, setDocentAudioUrl] = useState(null);
  const [docentLoading, setDocentLoading] = useState(false);
  const [docentError, setDocentError] = useState(null);
  const [docentLang, setDocentLang] = useState('ko');

  const [tripRecordOpen, setTripRecordOpen] = useState(false);
  const [pendingFinishOpen, setPendingFinishOpen] = useState(false);
  /** 상태 악화 알림이 문제 장소를 지목했을 때("&item=") 그 카드로 스크롤+펄스하기 위한 딥링크 대상 */
  const [highlightItemId, setHighlightItemId] = useState(null);
  /** "방문 예정 시각 지남 + 미완료" nudge - "아직이에요" 누른 시각(itemId별). 30분 지나면 재노출. */
  const [visitNudgeSnoozedAt, setVisitNudgeSnoozedAt] = useState({});
  /** 위 nudge를 시간 경과에 따라 다시 평가시키기 위한 1분 주기 tick - setter로 리렌더만 트리거,
   *  값 자체는 안 읽으므로 lint 컨벤션대로 밑줄 접두사(_)를 붙인다. */
  const [_visitNudgeTick, setVisitNudgeTick] = useState(0);
  const [tripSubmitting, setTripSubmitting] = useState(false);
  const [rerouteCount, setRerouteCount] = useState(0);

  const [autoReplacing, setAutoReplacing] = useState(false);
  const [autoReplaceNotice, setAutoReplaceNotice] = useState(null);
  const [closingGate, setClosingGate] = useState(null);
  const [hoursWarning, setHoursWarning] = useState(null);
  const hoursWarningResolverRef = useRef(null);
  const [rerouteLoading, setRerouteLoading] = useState(false);
  const [optimizeLoading, setOptimizeLoading] = useState(false);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const [suggestLoading, setSuggestLoading] = useState(false);
  const [suggestApplying, setSuggestApplying] = useState(false);
  const [suggestResult, setSuggestResult] = useState(null);
  const [suggestError, setSuggestError] = useState(null);
  const [sortByTimeLoading, setSortByTimeLoading] = useState(false);
  const [shareBusy, setShareBusy] = useState(false);
  const autoOptimizedRef = useRef(false);
  const autoRecordPromptedRef = useRef(false);
  // 동선 최적화용 GPS 권한 거부를 세션(이 컴포넌트 생존 기간) 내에서 기억 - 한 번 거부하면
  // "동선 최적화"/"이 순서 어때요?"를 다시 눌러도 재요청하지 않고 서버 폴백(남은 첫 슬롯)으로 보낸다.
  const geoDeniedRef = useRef(false);
  const [activeDate, setActiveDate] = useState(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [tripSection, setTripSection] = useState('home');

  useEffect(() => {
    function onHash() {
      setShareToken(readShareTokenFromHash());
    }
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // 알림 탭으로 새 탭이 열린 경우 - sw.js가 붙여준 "?open={itineraryId}"를 읽어 그 일정으로 바로 진입.
  // 마무리 알림은 "&finish=1", 상태 악화 알림은 문제 장소를 "&item={itemId}"로 실어 보낸다
  // (NotificationSchedulerService.dispatch 참고) - 있으면 그 카드로 스크롤+하이라이트한다.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const openId = params.get('open');
    if (!openId) return;
    resumeDraftItinerary(openId);
    if (params.get('finish') === '1') setPendingFinishOpen(true);
    const itemId = params.get('item');
    if (itemId) setHighlightItemId(itemId);
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
      const url = new URL(event.data.url, window.location.origin);
      const openId = url.searchParams.get('open');
      if (!openId) return;
      resumeDraftItinerary(openId);
      if (url.searchParams.get('finish') === '1') setPendingFinishOpen(true);
      const itemId = url.searchParams.get('item');
      if (itemId) setHighlightItemId(itemId);
      navigate('/trip');
    }
    navigator.serviceWorker?.addEventListener('message', onMessage);
    return () => navigator.serviceWorker?.removeEventListener('message', onMessage);
  }, [resumeDraftItinerary, navigate]);

  // 하이라이트 대상 카드가 실제로 DOM에 나타나면(일정 로드 완료) 스크롤 후, 펄스 애니메이션이
  // 끝날 시간(펄스 2회×1.6s) 뒤에 상태를 지워 다음에 같은 카드를 다시 여는 것도 감지되게 한다.
  useEffect(() => {
    if (!highlightItemId || !itinerary || tripSection !== 'home') return;
    const el = document.getElementById(`item-${highlightItemId}`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const timer = setTimeout(() => setHighlightItemId(null), 3400);
    return () => clearTimeout(timer);
  }, [highlightItemId, itinerary, tripSection]);

  useEffect(() => {
    if (!pendingFinishOpen || !itinerary) return;
    navigate('/trip');
    setTripRecordOpen(true);
    setPendingFinishOpen(false);
  }, [pendingFinishOpen, itinerary, navigate]);

  // "방문 예정 시각 지남" nudge를 1분마다 다시 평가한다(스누즈 30분 경과 여부 포함) - 서버 폴링과
  // 무관하게 순수 시간 경과만으로 판단하므로 별도의 가벼운 로컬 타이머로 충분하다.
  useEffect(() => {
    const timer = setInterval(() => setVisitNudgeTick((t) => t + 1), 60000);
    return () => clearInterval(timer);
  }, []);

  // 알림 권한이 이미 있으면 FCM 토큰을 서버에 등록(팝업 없음). 여행이 생기면 itineraryId도 보강.
  useEffect(() => {
    if (!sessionId) return;
    syncPushSubscription(sessionId, itineraryId).catch(() => {});
  }, [sessionId, itineraryId]);

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

  function handleGoHome() {
    setMenuOpen(false);
    leaveItineraryView();
    navigate('/');
  }

  /**
   * 특정 일정을 "항상 최신 상태로" 열어 /trip으로 이동한다.
   * resumeDraftItinerary만 쓰면 itineraryId가 이미 그 값일 때(재개) [itineraryId] 로드 이펙트가
   * 재실행되지 않아 이전에 화면에 있던 옛 itinerary가 그대로 보이는 문제가 있었다
   * (중복 일정 모달 "기존 일정 수정"에서 재현). 여기서 명시적으로 새로 받아 상태를 갱신한다.
   */
  async function openItineraryFresh(id) {
    if (id == null) return;
    try {
      const fresh = await api.getItinerary(id);
      resumeDraftItinerary(id);
      setItinerary(fresh);
      setActiveDate(fresh.startDate);
      navigate('/trip');
    } catch (e) {
      setCreateError(e.message || '일정을 불러오지 못했어요');
    }
  }

  function handleResumeDraft(id) {
    openItineraryFresh(id);
  }

  function handleOpenMyTrips() {
    setMenuOpen(false);
    navigate('/my-trips');
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
  // 오늘 일정을 전부 다녀오면(완료) 한 번만 후기 플로우로 연결한다. 스킵(안 간 곳)도 completed로 잡히므로
  // "모두 완료 = 하루가 끝났다"로 본다. 되돌리기로 다시 미완료가 되면 재프롬프트하지 않는다(ref 가드).
  const allDayItemsDone = visibleItems.length > 0 && visibleItems.every((i) => i.completed);
  useEffect(() => {
    if (!itinerary || !isTripToday(tripDate)) return;
    if (!allDayItemsDone || tripRecordOpen || autoRecordPromptedRef.current) return;
    autoRecordPromptedRef.current = true;
    setTripRecordOpen(true);
  }, [allDayItemsDone, itinerary, tripDate, tripRecordOpen]);

  // "OO 다녀오셨나요?" nudge 대상 - 오늘 일정 중 방문 예정 시각이 지났는데 미완료인 첫 항목.
  // 긴급(혼잡·날씨·폭염 등) 트리거가 떠 있으면 그게 우선이라 이 nudge는 숨긴다(방문 완료 UX
  // 개선 스펙 오픈 이슈 확정: 혼잡도·열기·날씨보다 낮게, 기본 순풍보다는 위). "아직이에요"로
  // 스누즈하면 30분 뒤 visitNudgeTick(1분 주기)이 자연히 다시 노출시킨다 - 별도 서버 저장 불필요.
  const visitNudgeUrgentTrigger = trigger?.level === 'WARNING' || trigger?.level === 'DANGER';
  const visitNudgeCandidate = isTripToday(tripDate)
    ? overdueUnconfirmedItem(visibleItems, new Date().getHours() * 60 + new Date().getMinutes())
    : null;
  const visitNudgeSnoozedUntil = visitNudgeCandidate
    ? visitNudgeSnoozedAt[visitNudgeCandidate.itemId]
    : null;
  const visitNudgeSnoozed = visitNudgeSnoozedUntil != null
    && Date.now() - visitNudgeSnoozedUntil < 30 * 60 * 1000;
  const visitNudgeItem = !visitNudgeUrgentTrigger && !visitNudgeSnoozed ? visitNudgeCandidate : null;

  function handleDismissVisitNudge(itemId) {
    setVisitNudgeSnoozedAt((prev) => ({ ...prev, [itemId]: Date.now() }));
  }

  const searchOriginPlaces = visibleItems.filter((item) => item.contentId);
  const pinnedToday = searchOriginPlaces.filter((item) => item.pinned);
  const defaultSearchOrigin = pinnedToday.length > 0
    ? pinnedToday[pinnedToday.length - 1]
    : searchOriginPlaces[0];
  const defaultSearchOriginId = defaultSearchOrigin ? String(defaultSearchOrigin.itemId) : '';

  function selectTripSection(key) {
    // "홈" 탭은 /trip 내부 섹션이 아니라 메인(CreateTripScreen)으로 나가는 액션 - 기존 상단
    // "← 메인" 버튼(handleGoHome)과 동일하게 처리한다(2026-09-11 핸드오프 브리프).
    if (key === 'main') {
      handleGoHome();
      return;
    }
    if (!TRIP_SECTIONS.includes(key)) return;
    setTripSection(key);
    const next = tripSectionPath(key);
    if (`${location.pathname}${location.hash}` !== next) {
      navigate(next, { replace: true });
    }
  }

  useEffect(() => {
    if (!itinerary || location.pathname !== '/trip') return;
    setTripSection(tripSectionFromHash(location.hash));
    window.scrollTo(0, 0);
  }, [itinerary, location.pathname, location.hash]);

  const refreshTrigger = useCallback(async () => {
    if (!itineraryId) return;
    const origin = await getCurrentPositionSafe();
    api.getTriggerStatus(itineraryId, origin).then(setTrigger).catch(() => {});
  }, [itineraryId]);

  // 액션 즉시 피드백(낙관적 UI · 즉시 트리거 해소 · 실패 롤백 · 2초 토스트) 공통 훅.
  // 동선 최적화 / 혼잡도(비·폭염) 대안이 같은 패턴을 공유한다.
  const {
    toast, optimistic: pinwheelOptimistic, rollback: pinwheelRollback,
    beginOptimistic, commitResolve, rollbackResolve, cancelOptimistic, dismissToast, showToast,
  } = useResolveFeedback({ triggerRef, setTrigger, refreshTrigger });

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
      setCreatingStage('이 지역 축제와 인기 스팟으로 오전·오후 일정을 만들고 있어요...');
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
          ...placeSnapshotFields(stop),
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
   * 여행 생성 직후 - 첫 화면에서 미리 등록한 고정 일정(앵커)이 있으면 스마트 동선 대신 이 장소를
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
          ...placeSnapshotFields(stop),
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

  /** 중복 안내 모달 - "기존 일정 수정" 선택 시 그 일정을 최신 상태로 열어 편집 */
  function handleEditExistingItinerary() {
    const id = duplicateConflict?.existing?.itineraryId;
    setDuplicateConflict(null);
    openItineraryFresh(id);
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

  /**
   * 시간 수정·추가 실패를 사용자에게 반드시 보여준다.
   * TIME_OVERLAP(409) → "시간 겹침", CLOSING_TIME_INFEASIBLE(422) → "마감 임박".
   * 대안 시각이 오면 모달에서 바로 다시 넣을 수 있다.
   */
  function reportScheduleGate(e, placeName, retryWithTime) {
    const code = e?.data?.errorCode;
    const suggestedTimes = e?.data?.suggestedTimes || [];
    if (code === 'TIME_OVERLAP' || (e?.status === 409 && e?.data?.conflictingPlaceName)) {
      setClosingGate({
        kind: 'CONFLICT',
        placeName,
        message: e.data.message,
        suggestedTimes,
        retryWithTime,
      });
      return true;
    }
    if (code === 'CLOSING_TIME_INFEASIBLE' || e?.status === 422) {
      setClosingGate({
        kind: 'CLOSING',
        placeName,
        message: e.data?.message || e.message,
        suggestedTimes,
        retryWithTime,
      });
      return true;
    }
    return false;
  }

  function reportIfTimeConflict(e, itemId) {
    const placeName = itinerary?.items?.find((i) => i.itemId === itemId)?.placeName;
    if (reportScheduleGate(e, placeName, (time) => handleUpdateTime(itemId, time))) {
      return;
    }
    setAutoReplaceNotice(`${placeName ? `"${placeName}" ` : ''}시간을 바꾸지 못했어요. ${e?.message || '다시 시도해 주세요.'}`);
    setTimeout(() => setAutoReplaceNotice(null), 5000);
  }

  async function handleUpdateTime(itemId, scheduledTime) {
    const item = itinerary?.items?.find((i) => i.itemId === itemId);
    const { proceed, check } = await confirmHoursIfNeeded({
      contentId: item?.contentId,
      contentTypeId: item?.contentTypeId,
      placeName: item?.placeName,
      restDateText: item?.restDateText,
      closeTime: item?.closeTime,
      useTimeText: item?.useTimeText,
      scheduledTime,
      visitDate: item?.visitDate || activeDate,
      confirmLabel: '그래도 저장',
    });
    if (!proceed) return;
    try {
      const result = await api.updateItem(itineraryId, itemId, {
        scheduledTime,
        acknowledgeHoursWarning: check?.warning || undefined,
      });
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

  /** 지난 일정(완료) 수동 토글 - 스킵/조기 완료, 또는 완료 항목을 다시 진행 중으로 되돌리기 */
  async function handleToggleComplete(itemId, completed) {
    if (!itineraryId) return;
    try {
      const result = await api.setItemCompletion(itineraryId, itemId, completed);
      setItinerary(result);
      refreshTrigger();
      showToast('success', completed ? '다녀온 곳으로 표시했어요' : '다시 진행 중으로 되돌렸어요');
    } catch (e) {
      showToast('error', e?.message || '처리하지 못했어요, 다시 시도해주세요');
    }
  }

  async function handleDeleteItem(itemId) {
    try {
      const result = await api.deleteItem(itineraryId, itemId);
      if (result.autoReplacedPlaceName) {
        setAutoReplaceNotice(`"${result.autoReplacedPlaceName}"로 자동 채워드렸어요.`);
        setTimeout(() => setAutoReplaceNotice(null), 5000);
      }
      setItinerary(result);
      return true;
    } catch (e) {
      setAutoReplaceNotice(`삭제하지 못했어요. ${e?.message || '다시 시도해 주세요.'}`);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
      return false;
    }
  }

  async function handleSearch({ tags, maxBudgetPerPerson, originContentId, originContentTypeId }) {
    setRecoLoading(true);
    try {
      const excludeContentIds = itinerary.items.map((i) => i.contentId).filter(Boolean);
      const results = await api.getRecommendations({
        regionCode: itinerary.signguFullCode,
        withPet: itinerary.withPet,
        strollerFriendly: itinerary.strollerFriendly,
        accessibleFriendly: itinerary.accessibleFriendly,
        companionType: itinerary.companionType,
        adultAgeGroup: itinerary.adultAgeGroup,
        childAges: itinerary.childAges,
        tags,
        maxBudgetPerPerson,
        excludeContentIds,
        originContentId,
        originContentTypeId,
        // 지금 시각 기준 영업 상태(영업중/휴무/영업종료) 배지가 정확히 뜨려면 방문일이 필요하다
        // (2026-09-11 사용자 제보 - 21시 넘어도 "영업중"으로 뜨던 문제. 없으면 백엔드가 "미래
        // 방문 계획"으로 보고 무조건 OPEN으로 찍음).
        visitDate: activeDate || itinerary.startDate,
      });
      setRecoResults(results);
    } catch {
      setRecoResults([]);
    } finally {
      setRecoLoading(false);
    }
  }

  /**
   * 일정 추가/시간 수정 직전 휴무·마감 경고. 막지 않고 confirm만 받는다.
   * 스마트 동선 자동 담기는 이 경로를 타지 않는다.
   */
  function resolveHoursWarning(proceed) {
    const resolve = hoursWarningResolverRef.current;
    hoursWarningResolverRef.current = null;
    setHoursWarning(null);
    resolve?.(proceed);
  }

  async function confirmHoursIfNeeded({
    contentId,
    contentTypeId,
    placeName,
    restDateText,
    closeTime,
    useTimeText,
    scheduledTime,
    visitDate,
    confirmLabel = '그래도 담기',
  }) {
    if (!itineraryId) return { proceed: true, check: null };
    let check;
    try {
      check = await api.checkPlaceHours(itineraryId, {
        contentId,
        contentTypeId,
        placeName,
        restDateText,
        closeTime,
        useTimeText,
        scheduledTime,
        visitDate,
      });
    } catch {
      return { proceed: true, check: null };
    }
    if (!check?.warning) {
      return { proceed: true, check };
    }
    const proceed = await new Promise((resolve) => {
      hoursWarningResolverRef.current = resolve;
      setHoursWarning({ placeName, warnings: check.warnings, confirmLabel });
    });
    return { proceed, check };
  }

  /**
   * "일정에 추가" — 시각이 명시되면 서버가 같은 날 겹침(TIME_OVERLAP)을 마감보다 먼저 막는다.
   * 거절되면 겹침/마감 모달에 대안 시각을 띄워 그 시각으로 다시 넣을 수 있게 한다.
   * 휴무·마감은 먼저 경고 confirm을 받고, 사용자가 넘기면 저장을 막지 않는다.
   */
  async function addCandidateToItinerary(candidate, visitDate = activeDate, isAlternate = false, options = {}) {
    const scheduledTime = candidate.scheduledTime || candidate.suggestedTime;
    let restDateText = candidate.restDateText;
    let closeTime = candidate.closeTime;
    let useTimeText = candidate.useTimeText;
    let acknowledgeHoursWarning = Boolean(options.acknowledgeHoursWarning);

    if (!acknowledgeHoursWarning) {
      const { proceed, check } = await confirmHoursIfNeeded({
        contentId: candidate.contentId,
        contentTypeId: candidate.contentTypeId,
        placeName: candidate.placeName,
        restDateText,
        closeTime,
        useTimeText,
        scheduledTime,
        visitDate,
      });
      if (!proceed) return null;
      if (check) {
        restDateText = check.restDateText || restDateText;
        closeTime = check.closeTime || closeTime;
        useTimeText = check.useTimeText || useTimeText;
        if (check.warning) acknowledgeHoursWarning = true;
      }
    }

    let result;
    try {
      result = await api.addItem(itineraryId, {
        contentId: candidate.contentId,
        contentTypeId: candidate.contentTypeId,
        placeName: candidate.placeName,
        thumbnailUrl: candidate.thumbnailUrl,
        scheduledTime,
        tags: candidate.matchedTags,
        crowdRate: candidate.crowdRate,
        visitDate,
        addr1: candidate.addr1,
        tel: candidate.tel,
        useFeeText: candidate.useFeeText,
        isFree: candidate.isFree,
        estimatedCostPerPerson: candidate.estimatedCostPerPerson,
        restDateText,
        closeTime,
        useTimeText,
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
        acknowledgeHoursWarning: acknowledgeHoursWarning || undefined,
        // 최초 일정 생성(스마트/자동 플랜 확정)은 이력에 안 남기고, 지도 검색·대안 카드에서
        // 사용자가 나중에 담을 때만 change_history(MANUAL)에 기록한다.
        logHistory: options.logHistory || undefined,
        ...placeSnapshotFields(candidate),
      });
    } catch (e) {
      const reported = reportScheduleGate(
        e,
        candidate.placeName,
        (time) => addCandidateToItinerary({ ...candidate, scheduledTime: time }, visitDate, isAlternate, {
          acknowledgeHoursWarning: true,
          logHistory: options.logHistory,
        }),
      );
      if (!reported) {
        setAutoReplaceNotice(`"${candidate.placeName || '이 장소'}"를 담지 못했어요. ${e?.message || '다시 시도해 주세요.'}`);
        setTimeout(() => setAutoReplaceNotice(null), 5000);
      }
      throw e;
    }
    setItinerary(result);
    return result;
  }

  async function handleAddFestival(festival) {
    setAddingFestivalId(festival.contentId);
    try {
      const result = await addCandidateToItinerary(festival);
      if (result) refreshTrigger();
    } finally {
      setAddingFestivalId(null);
    }
  }

  async function handleAddRecommendation(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      const result = await addCandidateToItinerary(candidate, activeDate, false, { logHistory: true });
      // 검색 탭에서 담으면 방금 담은 걸 바로 확인할 수 있게 "일정" 탭으로 이동한다(2026-09-11
      // 사용자 요청). 마감 경고를 취소해 실제로 안 담겼으면(result=null) 이동하지 않는다.
      if (result) selectTripSection('home');
    } catch {
      /* 마감 게이트 등 — ClosingGateModal / 서버 메시지로 안내 */
    } finally {
      setAddingContentId(null);
    }
  }

  async function handleMapAddPlace(place) {
    setAddingContentId(place.contentId);
    try {
      const result = await addCandidateToItinerary({
        contentId: place.contentId,
        contentTypeId: Number.isFinite(Number(place.contentTypeId)) ? Number(place.contentTypeId) : undefined,
        placeName: place.placeName,
        thumbnailUrl: place.thumbnailUrl,
        addr1: place.addr1,
        tel: place.tel,
        mapX: place.mapX,
        mapY: place.mapY,
        category: place.category,
        cat3: place.cat3,
      }, activeDate, false, { logHistory: true });
      // 휴무 경고를 취소하면 null — 지도 낙관적 "담김"을 되돌려야 해서 throw
      if (!result) {
        throw new Error('not-added');
      }
      // 지도 탭에서 담으면 방금 담은 걸 바로 확인할 수 있게 "일정" 탭으로 이동한다(2026-09-11 사용자 요청).
      selectTripSection('home');
      return result;
    } finally {
      setAddingContentId(null);
    }
  }

  async function handleMapRemovePlace(contentId) {
    const item = visibleItems.find((i) => String(i.contentId) === String(contentId));
    if (!item) return;
    setAddingContentId(contentId);
    try {
      const ok = await handleDeleteItem(item.itemId);
      if (!ok) {
        throw new Error('remove failed');
      }
    } finally {
      setAddingContentId(null);
    }
  }

  /** 파이프라인 대안이 비면 근처 아무 장소(맛집·카페·쇼핑·관광)로라도 채운다 - "무조건 보여주기". */
  async function nearbyAnyCandidates() {
    const items = itinerary?.items || [];
    const origin = [...items].reverse().find((i) => i.mapX && i.mapY);
    if (!origin) return [];
    const nearby = await api
      .searchNearbyPlaces({ mapX: origin.mapX, mapY: origin.mapY, radius: 3000, numOfRows: 15 })
      .catch(() => []);
    const have = new Set(items.map((i) => String(i.contentId)));
    return (nearby || [])
      .filter((p) => p.contentId && !have.has(String(p.contentId)) && p.placeName)
      .slice(0, 8)
      .map((p) => ({
        contentId: p.contentId,
        contentTypeId: p.contentTypeId,
        placeName: p.placeName,
        thumbnailUrl: p.thumbnailUrl,
        addr1: p.addr1,
        tel: p.tel,
        mapX: p.mapX,
        mapY: p.mapY,
        category: p.category || '장소',
        matchedTags: p.category ? [`#${p.category}`] : [],
        oneLiner: p.dist != null ? `여기서 약 ${Math.round(p.dist)}m` : '근처에서 바로 갈 수 있어요',
        crowdRate: null,
      }));
  }

  async function handleRequestAlternatives(avoidHint) {
    setAltOpen(true);
    setAltLoading(true);
    setAltReason(null);
    setAltError(null);
    try {
      let { candidates, reason } = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      let resolvedReason = reason || (avoidHint === 'HEAT' ? 'HEAT_ALTERNATIVE' : avoidHint === 'WEATHER' ? 'RAIN_ALTERNATIVE' : avoidHint === 'CROWD' ? 'CROWD_ALTERNATIVE' : avoidHint === 'ROUTE' ? 'ROUTE_ALTERNATIVE' : null);
      if (!candidates?.length) {
        const nearby = await nearbyAnyCandidates();
        if (nearby.length) {
          candidates = nearby;
          resolvedReason = 'NEARBY_ANY';
        }
      }
      setAltCandidates(candidates || []);
      setAltReason(resolvedReason);
    } catch (e) {
      const nearby = await nearbyAnyCandidates().catch(() => []);
      if (nearby.length) {
        setAltCandidates(nearby);
        setAltReason('NEARBY_ANY');
      } else {
        setAltCandidates([]);
        setAltError(e?.message || '대안을 불러오지 못했어요. 잠시 후 다시 시도해주세요.');
      }
    } finally {
      setAltLoading(false);
    }
  }

  async function handleAddAlternative(candidate) {
    setAddingContentId(candidate.contentId);
    try {
      const result = await addCandidateToItinerary(candidate, activeDate, true, { logHistory: true });
      if (result) {
        setRerouteCount((n) => n + 1);
        await replanTimelineAfterAlternative(result);
        refreshTrigger();
      }
    } finally {
      setAddingContentId(null);
    }
  }

  /**
   * 대안을 담거나 교체한 뒤, 스마트/자동 일정 확정 때(confirmSmartPlanCore)와 똑같이
   * 실제 이동시간 기준으로 그날 동선·시간표를 다시 잡는다. 고정(pin)한 앵커는 백엔드가
   * 자리·시각을 그대로 유지한다.
   */
  async function replanTimelineAfterAlternative(current) {
    const day = activeDate || current?.startDate;
    const dayItemCount = (current?.items || []).filter(
      (i) => (i.visitDate || current.startDate) === day,
    ).length;
    if (dayItemCount < 2) return current;
    try {
      const replanned = await api.optimizeRoute(itineraryId, day);
      setItinerary(replanned);
      setAutoReplaceNotice(
        replanned.routeHint || '대안을 반영해 이동시간·체류 기준으로 시간표를 다시 짰어요.',
      );
      setTimeout(() => setAutoReplaceNotice(null), 5000);
      return replanned;
    } catch {
      return current;
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
        // 삭제+추가를 서버 한 트랜잭션으로(applyAlternative) - 실패해도 원본 슬롯이 안 사라지고
        // 변경 이력이 자동으로 한 건 쌓인다. 원래 시간대에 1순위가 마감 등으로 못 들어가면
        // 다음 후보로 계속 시도하고, 전부 실패했을 때만 안내한다.
        let result = null;
        let replacedWith = null;
        for (const candidate of candidates) {
          try {
            result = await api.applyAlternative(itineraryId, {
              removedItemId: affectedItem.itemId,
              newPlace: {
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
                ...placeSnapshotFields(candidate),
              },
              triggerType: avoidHint,
              reoptimize: false,
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
   * 비/폭염: 야외 → 실내. 혼잡: 붐비는 곳 → 한산한 곳. 기존 방문 시각은 유지.
   */
  /** 혼잡도/실내 대안 적용 토스트 — 첫 교체 장소 + 외 N곳 */
  function rerouteToastText(avoidHint, plannedTargets, usedSize) {
    const first = plannedTargets[0];
    const verb = avoidHint === 'CROWD'
      ? '혼잡도 낮은 곳으로 변경했어요'
      : (avoidHint === 'HEAT' || avoidHint === 'WEATHER')
        ? '실내로 바꿨어요'
        : '대체 장소로 바꿨어요';
    const more = usedSize > 1 ? ` 외 ${usedSize - 1}곳` : '';
    const where = first?.next?.placeName ? ` — ${first.next.placeName}${more}` : '';
    return `${verb}${where} ✅`;
  }

  async function handleRerouteSchedule(avoidHint) {
    setRerouteLoading(true);
    setAutoReplaceNotice(null);
    beginOptimistic(avoidHint); // 클릭 즉시 핀휠 성공 스킨 (대안이 없으면 아래에서 조용히 걷어냄)
    try {
      const { candidates, reason } = await api.getAlternatives(itineraryId, { avoid: avoidHint });
      const note = reason === 'RAIN_ALTERNATIVE'
        ? '비 소식에 맞춰 실내 일정으로 바꿨어요.'
        : reason === 'HEAT_ALTERNATIVE'
          ? '폭염 소식에 맞춰 실내 일정으로 바꿨어요.'
          : avoidHint === 'CROWD'
            ? '혼잡한 곳을 한산한 일정으로 바꿨어요.'
            : '대체 일정으로 바꿨어요.';

      if (!candidates?.length) {
        cancelOptimistic(); // 성공 아님 — 낙관적 스킨만 조용히 걷어냄(원래 트리거 복원)
        setAutoReplaceNotice('지금은 바꿀 대안이 없어요. 후보만 먼저 볼게요.');
        await handleRequestAlternatives(avoidHint);
        return;
      }

      const crowdIds = (trigger?.crowdAffectedItemIds || []).map(Number);
      const weatherIds = (trigger?.weatherAffectedItemIds?.length
        ? trigger.weatherAffectedItemIds
        : (trigger?.affectedItemIds || [])).map(Number);
      const affectedIds = avoidHint === 'CROWD' ? crowdIds : weatherIds;
      const affectedItems = itinerary.items.filter((i) => affectedIds.includes(Number(i.itemId)) && !i.pinned);
      const targets = affectedItems.length > 0
        ? affectedItems
        : itinerary.items.filter((i) => {
            if (i.pinned) return false;
            if ((i.visitDate || itinerary.startDate) !== activeDate) return false;
            if (avoidHint === 'CROWD') return (i.crowdRate ?? 0) >= 70;
            const tags = i.tags || [];
            if (tags.includes('#실내') || tags.includes('#맛집') || tags.includes('#카페')) return false;
            return true;
          });

      if (targets.length === 0) {
        cancelOptimistic();
        setAltCandidates(candidates);
        setAltReason(avoidHint === 'CROWD' ? 'CROWD_ALTERNATIVE' : undefined);
        setAltOpen(true);
        setAutoReplaceNotice(avoidHint === 'CROWD'
          ? '바꿀 혼잡 일정이 없어 한산한 후보만 보여드려요.'
          : '교체할 야외 일정이 없어 후보만 보여드려요.');
        return;
      }

      // 각 교체를 applyAlternative로(삭제+추가 원자적). 마지막 교체에 reoptimize=true를 줘서
      // 이동시간 기준 동선·시간표 재계산까지 서버에서 한 번에 끝낸다. 교체 건마다 변경 이력이 쌓인다.
      let result = itinerary;
      const used = new Set();
      const plannedTargets = [];
      for (const target of targets) {
        const next = candidates.find((c) => c.contentId && !used.has(c.contentId)
          && !result.items.some((it) => it.contentId === c.contentId));
        if (!next) break;
        used.add(next.contentId);
        plannedTargets.push({ target, next });
      }

      if (plannedTargets.length === 0) {
        // 후보는 있었지만 이미 담긴 곳과 겹쳐 실제 교체가 0건 — 성공 아님, 후보만 보여줌
        cancelOptimistic();
        setAltCandidates(candidates);
        setAltReason(avoidHint === 'CROWD' ? 'CROWD_ALTERNATIVE' : undefined);
        setAltOpen(true);
        setAutoReplaceNotice('새로 넣을 만한 대안이 없어 후보만 보여드려요.');
        return;
      }

      for (let i = 0; i < plannedTargets.length; i++) {
        const { target, next } = plannedTargets[i];
        result = await api.applyAlternative(itineraryId, {
          removedItemId: target.itemId,
          newPlace: {
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
            ...placeSnapshotFields(next),
          },
          triggerType: avoidHint,
          reoptimize: i === plannedTargets.length - 1,
        });
      }

      setItinerary(result);
      setRerouteCount((n) => n + used.size);
      // 폴링을 기다리지 않고 방금 해소한 변수를 즉시 제거 + 2초 토스트
      commitResolve(avoidHint, rerouteToastText(avoidHint, plannedTargets, used.size));

      setAutoReplaceNotice(
        `${note} (${used.size}곳 교체) ${result.routeHint || '이동시간 기준으로 시간표도 다시 짰어요.'}`,
      );
    } catch (e) {
      rollbackResolve(avoidHint); // 성공 상태 → 원래 상태로 되돌리는 트랜지션 + 실패 토스트
      setAutoReplaceNotice(`일정 교체 실패: ${e.message}`);
    } finally {
      setRerouteLoading(false);
      setTimeout(() => setAutoReplaceNotice(null), 6000);
    }
  }

  /** 변경 이력 패널에서 "되돌리기" 확인 후 호출 - targetSequence가 null이면 원본으로. */
  async function handleRevertPlan(targetSequence) {
    if (!itineraryId || reverting) return;
    setReverting(true);
    setAutoReplaceNotice(null);
    try {
      const result = await api.revertPlan(itineraryId, targetSequence);
      setItinerary(result);
      setHistoryOpen(false);
      refreshTrigger();
      setAutoReplaceNotice(
        targetSequence == null ? '원본 일정으로 되돌렸어요.' : `변경 이력 #${targetSequence} 시점으로 되돌렸어요.`,
      );
    } catch (e) {
      setAutoReplaceNotice(`되돌리기 실패: ${e.message}`);
    } finally {
      setReverting(false);
      setTimeout(() => setAutoReplaceNotice(null), 5000);
    }
  }

  function handleGenerateSmartPlan() {
    const date = smartPlanDate || itinerary?.startDate;
    return api.getSmartPlan(itineraryId, {
      placeCount: 5,
      date,
      standard: true,
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

  /** "3.0km → 1.9km" 스타일 토스트 — 액션 전 routeTangle 스냅샷 + 응답의 최적화 거리 */
  function routeToastText(tangleSnapshot, result) {
    const before = tangleSnapshot?.currentDistanceKm;
    const after = result?.optimizedDistanceKm ?? tangleSnapshot?.optimizedDistanceKm;
    if (before != null && after != null && before - after > 0.05) {
      return `동선을 정리했어요! ${before.toFixed(1)}km → ${after.toFixed(1)}km 🍃`;
    }
    return result?.routeHint || '동선이 최적화됐어요 🍃';
  }

  /** 동선 재계산 — GPS 있으면 시작점, 없으면 장소만으로 매트릭스 TSP + 시간표.
   *  startTime("HH:mm")을 주면 첫 장소 시각을 사용자가 지정한 그대로 고정한다. */
  async function handleOptimizeRoute(origin, startTime, recordHistory = false) {
    if (!itineraryId || optimizeLoading) return;
    autoOptimizedRef.current = true;
    setOptimizeLoading(true);
    setAutoReplaceNotice(null);
    // 사용자가 명시적으로 누른 "동선 재계산"만 변경 이력(ROUTE)으로 남기고, 낙관적 UI·즉시
    // 트리거 해소·토스트를 붙인다. GPS 자동 재계산 등 내부 호출은 그대로 조용히 처리한다.
    const tangleSnapshot = recordHistory ? triggerRef.current?.routeTangle : null;
    try {
      const result = recordHistory
        ? await api.applyReroute(itineraryId, activeDate, origin, startTime, '동선 재계산')
        : await api.optimizeRoute(itineraryId, activeDate, origin, startTime);
      setItinerary(result);
      if (recordHistory) {
        // 낙관적 스킨은 handleOptimizeFromGps에서 클릭 즉시 켜 둠 → 여기서 확정
        commitResolve('ROUTE', routeToastText(tangleSnapshot, result));
      } else {
        setAutoReplaceNotice(
          result.routeHint
            || (origin
              ? '현재 위치를 반영해 동선을 다시 계산했어요.'
              : '이동시간·체류를 반영해 동선을 다시 계산했어요.'),
        );
        refreshTrigger();
        setTimeout(() => setAutoReplaceNotice(null), 6000);
      }
    } catch (e) {
      if (recordHistory) {
        rollbackResolve('ROUTE'); // 성공 상태 → 원래 상태로 되돌리는 트랜지션 + 실패 토스트
      } else {
        setAutoReplaceNotice(`동선 재계산 실패: ${e.message}`);
        setTimeout(() => setAutoReplaceNotice(null), 6000);
      }
    } finally {
      setOptimizeLoading(false);
    }
  }

  /** 오늘 동선 「동선 재계산」 — 완료한 곳이 있으면 그 좌표가 서버 쪽 출발 앵커라 GPS를 켜지 않는다.
   *  완료한 곳이 없을 때만 GPS를 시도하고(권한 거부는 세션 내 기억), startTime을 지정했으면
   *  GPS 위치와 무관하게 그 시각을 첫 장소 도착 시각으로 고정한다. */
  function handleOptimizeFromGps(startTime) {
    if (!itineraryId || optimizeLoading) return;
    setOptimizeLoading(true);
    beginOptimistic('ROUTE'); // 클릭 즉시 핀휠을 성공 스킨으로 (API 응답 대기 없이)
    const done = (origin) => handleOptimizeRoute(origin, startTime, true);
    const hasCompletedToday = visibleItems.some((i) => i.completed);
    if (hasCompletedToday || geoDeniedRef.current || !navigator.geolocation) {
      done(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => done({ lon: pos.coords.longitude, lat: pos.coords.latitude }),
      () => { geoDeniedRef.current = true; done(null); }, // 위치 거부·실패여도 서버가 남은 첫 슬롯으로 폴백
      { enableHighAccuracy: true, timeout: 5000, maximumAge: 60000 },
    );
  }

  /** 「이 순서 어때요?」 — 완료한 곳이 있으면 그 좌표가 출발 앵커라 GPS를 켜지 않는다.
   *  확인 전까지 일정에 쓰지 않는다. */
  function handleSuggestRoute() {
    if (!itineraryId || suggestLoading || optimizeLoading) return;
    setSuggestOpen(true);
    setSuggestLoading(true);
    setSuggestError(null);
    setSuggestResult(null);
    const run = (origin) => {
      api.suggestRoute(itineraryId, activeDate, origin)
        .then((result) => setSuggestResult(result))
        .catch((e) => setSuggestError(e.message || '순서를 제안하지 못했어요'))
        .finally(() => setSuggestLoading(false));
    };
    const hasCompletedToday = visibleItems.some((i) => i.completed);
    if (hasCompletedToday || geoDeniedRef.current || !navigator.geolocation) {
      run(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => run({ lon: pos.coords.longitude, lat: pos.coords.latitude }),
      () => { geoDeniedRef.current = true; run(null); },
      { enableHighAccuracy: true, timeout: 5000, maximumAge: 60000 },
    );
  }

  async function handleApplySuggestedRoute() {
    if (!itineraryId || !suggestResult?.suggestedStops?.length || suggestApplying) return;
    setSuggestApplying(true);
    setSuggestError(null);
    try {
      const stops = suggestResult.suggestedStops.map((s) => ({
        itemId: s.itemId,
        scheduledTime: s.scheduledTime,
      }));
      const result = await api.applySuggestedRoute(itineraryId, activeDate, stops);
      setItinerary(result);
      setSuggestOpen(false);
      setSuggestResult(null);
      setAutoReplaceNotice(suggestResult.message || '제안한 순서로 오늘 일정을 바꿨어요.');
      refreshTrigger();
      setTimeout(() => setAutoReplaceNotice(null), 6000);
    } catch (e) {
      setSuggestError(e.message || '순서를 반영하지 못했어요');
    } finally {
      setSuggestApplying(false);
    }
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
      // 모달 쪽에서 skipNextRestore를 먼저 호출하므로 history.back()과 겹치지 않는다.
      navigate('/', { replace: true });
    } catch (e) {
      alert(e.message);
      throw e;
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
    return <div className="app-loading">불러오는 중...</div>;
  }

  return (
    <>
      <GlobalMenu
        open={menuOpen}
        onClose={() => setMenuOpen(false)}
        onNavigateMyTrips={handleOpenMyTrips}
        onNavigateGuide={handleOpenGuide}
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
              <header className="app-header">
                <div className="header-inner">
                  {/* 상단 "← 메인" 버튼은 제거됨(2026-09-11) - 하단 탭바의 신규 "홈" 탭이 같은
                      역할(handleGoHome)을 대체한다. */}
                  <div className="header-trip-meta">
                    <span className="header-trip-region">{itinerary.regionDisplayName || '바람따라'}</span>
                    {tripDate && <span className="header-trip-date">{formatTripDate(tripDate)}</span>}
                  </div>
                  <div className="header-actions">
                    <button
                      type="button"
                      className="icon-btn header-menu-btn"
                      aria-label="전체 메뉴"
                      onClick={() => setMenuOpen(true)}
                    >
                      ☰
                    </button>
                    <button className="btn-share" type="button" onClick={handleShareItinerary} disabled={shareBusy}>
                      {shareBusy ? '준비 중...' : '공유'}
                    </button>
                  </div>
                </div>
              </header>

              <main className="app-main">
                {tripSection === 'home' && (
                <section className="trip-page-section">
                <PinwheelHero
                  compactWhenIdle
                  trigger={trigger}
                  optimistic={pinwheelOptimistic}
                  rollbackAnimating={pinwheelRollback}
                  onRequestAlternatives={handleRequestAlternatives}
                  loading={altLoading}
                  onRerouteSchedule={handleRerouteSchedule}
                  rerouteLoading={rerouteLoading}
                  onOptimizeRoute={() => handleOptimizeFromGps()}
                  optimizeLoading={optimizeLoading}
                />

                <VisitConfirmationNudge
                  item={visitNudgeItem}
                  onConfirm={() => handleToggleComplete(visitNudgeItem.itemId, true)}
                  onDismiss={() => handleDismissVisitNudge(visitNudgeItem.itemId)}
                />

                {autoReplaceNotice && <div className="auto-replace-notice">⚡ {autoReplaceNotice}</div>}

                <Toast toast={toast} onDismiss={dismissToast} />

                {itinerary.changeHistory?.length > 0 && (
                  <div className="daytrip-chip-row">
                    <button
                      type="button"
                      className="plan-history-open-btn"
                      onClick={() => setHistoryOpen(true)}
                    >
                      🕓 변경 이력 {itinerary.changeHistory.length}
                    </button>
                  </div>
                )}

                <ItineraryList
                  items={visibleItems}
                  affectedItemIds={trigger?.affectedItemIds}
                  weatherAffectedItemIds={trigger?.weatherAffectedItemIds}
                  businessAffectedItemIds={trigger?.businessAffectedItemIds}
                  closedDayAffectedItemIds={trigger?.closedDayAffectedItemIds}
                  hoursEndedAffectedItemIds={trigger?.hoursEndedAffectedItemIds}
                  crowdAffectedItemIds={trigger?.crowdAffectedItemIds}
                  tightTimingItemIds={itinerary?.tightTimingItemIds}
                  weatherAlert={Boolean(trigger?.weatherTrigger || trigger?.heatTrigger)}
                  rainAlert={Boolean(trigger?.weatherTrigger)}
                  heatAlert={Boolean(trigger?.heatTrigger)}
                  trigger={trigger}
                  dayLabel={isTripToday(tripDate) ? '오늘' : (tripDate ? formatTripDate(tripDate) : null)}
                  highlightedItemId={highlightItemId}
                  onUpdateTime={handleUpdateTime}
                  onUpdateItem={handleUpdateItem}
                  onTogglePin={handleTogglePin}
                  onDelete={handleDeleteItem}
                  onToggleComplete={handleToggleComplete}
                  onOpenDocent={handleOpenDocent}
                  onOpenHistory={itinerary.changeHistory?.length > 0 ? () => setHistoryOpen(true) : undefined}
                  onSortByTime={handleSortByTime}
                  sortByTimeLoading={sortByTimeLoading}
                  onOptimizeFromGps={handleOptimizeFromGps}
                  gpsOptimizing={optimizeLoading}
                />

                <FestivalBanner
                  festivals={trigger?.festivalSuggestions}
                  onAdd={handleAddFestival}
                  addingId={addingFestivalId}
                />
                </section>
                )}

                {tripSection === 'map' && (
                <section className="trip-page-section">
                  <header className="trip-section-head">
                    <h2>지도</h2>
                    <p className="trip-section-lead">지도에서 마커를 눌러 일정에 추가해보세요</p>
                  </header>
                  <DayRouteMap
                    items={visibleItems}
                    weatherAffectedItemIds={trigger?.weatherAffectedItemIds}
                    closedDayAffectedItemIds={trigger?.closedDayAffectedItemIds}
                    hoursEndedAffectedItemIds={trigger?.hoursEndedAffectedItemIds}
                    crowdAffectedItemIds={trigger?.crowdAffectedItemIds}
                    onAddPlace={handleMapAddPlace}
                    onRemovePlace={handleMapRemovePlace}
                    busyContentId={addingContentId}
                  />
                </section>
                )}

                {tripSection === 'search' && (
                <section className="trip-page-section">
                  <header className="trip-section-head">
                    <h2>검색</h2>
                    <p className="trip-section-lead">원하는 카테고리로 근처 장소를 찾아보세요</p>
                  </header>
                  <RecommendationSearch
                    onSearch={handleSearch}
                    onAdd={handleAddRecommendation}
                    results={recoResults}
                    loading={recoLoading}
                    addingId={addingContentId}
                    originPlaces={searchOriginPlaces}
                    defaultOriginItemId={defaultSearchOriginId}
                  />
                </section>
                )}

                {tripSection === 'alerts' && (
                <section className="trip-page-section">
                  <header className="trip-section-head">
                    <h2>알림</h2>
                  </header>
                  <AlertFeedScreen itineraryId={itineraryId} showTitle={false} />
                  <WeatherBanner items={weatherItems} />
                  <MidWeatherBanner forecast={midWeather} />
                </section>
                )}

                {tripSection === 'profile' && (
                <section className="trip-page-section">
                  <header className="trip-section-head">
                    <h2>프로필</h2>
                  </header>
                  <SettingsScreen
                    sessionId={sessionId}
                    itineraryId={itineraryId}
                    onFinishTrip={() => setTripRecordOpen(true)}
                  />
                </section>
                )}
              </main>

              <BottomTabBar active={tripSection} onSelect={selectTripSection} />

              <PlanHistoryPanel
                open={historyOpen}
                originalPlan={itinerary.originalPlan}
                changeHistory={itinerary.changeHistory}
                reverting={reverting}
                onRevert={handleRevertPlan}
                onClose={() => setHistoryOpen(false)}
              />

              <AlternativesPanel
                open={altOpen}
                candidates={altCandidates}
                loading={altLoading}
                reason={altReason}
                error={altError}
                onRetry={() => handleRequestAlternatives(
                  trigger?.heatTrigger ? 'HEAT'
                    : trigger?.weatherTrigger ? 'WEATHER'
                    : trigger?.crowdTrigger ? 'CROWD'
                    : trigger?.travelTimeTrigger ? 'ROUTE'
                    : (trigger?.closedDayTrigger || trigger?.hoursEndedTrigger) ? 'BUSINESS'
                    : undefined
                )}
                onAdd={handleAddAlternative}
                addingId={addingContentId}
                onApplyAll={() => handleRerouteSchedule(
                  trigger?.heatTrigger ? 'HEAT'
                    : trigger?.weatherTrigger ? 'WEATHER'
                    : trigger?.crowdTrigger ? 'CROWD'
                    : undefined
                )}
                /* 일괄 버튼은 🔴 urgent(DANGER: 트리거 2개↑ 또는 비/폭염경보/혼잡긴급)에서만
                   보조 옵션으로 노출. 🟡 caution 이하에서는 카드 브라우징만. */
                bulkUrgent={trigger?.level === 'DANGER'}
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

              <HoursWarningModal
                open={Boolean(hoursWarning)}
                placeName={hoursWarning?.placeName}
                warnings={hoursWarning?.warnings}
                confirmLabel={hoursWarning?.confirmLabel}
                onCancel={() => resolveHoursWarning(false)}
                onConfirm={() => resolveHoursWarning(true)}
              />

              <ClosingGateModal
                open={Boolean(closingGate)}
                placeName={closingGate?.placeName}
                message={closingGate?.message}
                kind={closingGate?.kind}
                suggestedTimes={closingGate?.suggestedTimes}
                onPickTime={async (time) => {
                  const retry = closingGate?.retryWithTime;
                  setClosingGate(null);
                  if (!retry) return;
                  try {
                    await retry(time);
                  } catch {
                    /* retry 실패 시 reportScheduleGate가 모달을 다시 연다 */
                  }
                }}
                onClose={() => setClosingGate(null)}
              />

              <SuggestRouteCompare
                open={suggestOpen}
                loading={suggestLoading}
                applying={suggestApplying}
                error={suggestError}
                result={suggestResult}
                onApply={handleApplySuggestedRoute}
                onClose={() => {
                  if (suggestApplying) return;
                  setSuggestOpen(false);
                  setSuggestResult(null);
                  setSuggestError(null);
                }}
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
        path="/trip/map"
        element={<Navigate to={itinerary ? '/trip#map' : '/'} replace />}
      />
      <Route
        path="/trip/search"
        element={<Navigate to={itinerary ? '/trip#search' : '/'} replace />}
      />
      <Route
        path="/alerts"
        element={<Navigate to={itinerary ? '/trip#alerts' : '/'} replace />}
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
          itinerary ? <Navigate to="/trip#profile" replace /> : (
            <>
              <BackHeader title="프로필 · 설정" onMenuClick={() => setMenuOpen(true)} />
              <SettingsScreen sessionId={sessionId} itineraryId={itineraryId} />
            </>
          )
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
      <Route
        path="/traveler-profile"
        element={
          <>
            <BackHeader title="내 여행 정보" onMenuClick={() => setMenuOpen(true)} />
            <TravelerProfileScreen />
          </>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
