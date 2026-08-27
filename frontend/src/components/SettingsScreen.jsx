import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import useTextScale from '../hooks/useTextScale';
import {
  isIOS,
  isStandalone,
  isPushSupported,
  isPushOptedOut,
  loadFirebaseWebConfig,
  requestPushToken,
  syncPushSubscription,
  disablePush,
  setPushOptedOut,
} from '../utils/webPush';
import { registerPush } from '../api/windmillApi';

const TEXT_SCALE_OPTIONS = [
  { value: 'default', label: '기본' },
  { value: 'large', label: '크게' },
  { value: 'xl', label: '아주 크게' },
];

const PUSH_STATUS_LABEL = {
  denied: '브라우저에서 알림이 차단돼 있어요. 브라우저 설정에서 허용해 주세요.',
  unsupported: '이 브라우저·환경은 웹 푸시를 지원하지 않아요.',
  unconfigured: '알림 권한은 허용됐지만, 푸시 서버(Firebase)가 아직 연결되지 않아 휴대폰으로 보낼 수 없어요.',
  token_failed: '알림 권한은 허용됐지만 기기 등록에 실패했어요. 잠시 후 다시 시도해 주세요.',
  registered: '알림이 켜져 있어요. 순풍이면 첫 일정 30분 전과 여행 마무리를, 주황·빨강이면 바로 알려드려요.',
  disabled: '알림을 껐어요. 다시 켜면 일정 시작·마무리와 비·폭염·혼잡·동선 변수를 휴대폰으로 알려드려요.',
  disable_failed: '끄기에 실패했어요. 잠시 후 다시 시도해 주세요.',
};

/** 전체 메뉴 > 설정 - 글씨 크기(어르신 접근성), 알림(웹 푸시) */
export default function SettingsScreen({ sessionId, itineraryId }) {
  const navigate = useNavigate();
  const [textScale, setTextScale] = useTextScale();
  const [pushStatus, setPushStatus] = useState(() => (isPushOptedOut() ? 'disabled' : 'idle'));
  const [pushBusy, setPushBusy] = useState(false);

  useEffect(() => {
    if (!sessionId || !isPushSupported()) return;
    if (isPushOptedOut()) {
      setPushStatus('disabled');
      return;
    }
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
    let cancelled = false;
    syncPushSubscription(sessionId, itineraryId)
      .then(async (ok) => {
        if (cancelled) return;
        setPushStatus(ok ? 'registered' : await pushStatusAfterGrant());
      })
      .catch(() => {
        if (!cancelled) setPushStatus('token_failed');
      });
    return () => { cancelled = true; };
  }, [sessionId, itineraryId]);

  async function pushStatusAfterGrant() {
    const config = await loadFirebaseWebConfig();
    return config ? 'token_failed' : 'unconfigured';
  }

  async function handleEnablePush() {
    if (!isPushSupported()) {
      setPushStatus('unsupported');
      return;
    }
    setPushBusy(true);
    try {
      setPushOptedOut(false);
      const token = await requestPushToken();
      if (Notification.permission === 'denied') {
        setPushStatus('denied');
        return;
      }
      if (token) {
        await registerPush(sessionId, { fcmToken: token, itineraryId });
        setPushStatus('registered');
        return;
      }
      setPushStatus(await pushStatusAfterGrant());
    } catch {
      setPushStatus('token_failed');
    } finally {
      setPushBusy(false);
    }
  }

  async function handleDisablePush() {
    setPushBusy(true);
    try {
      await disablePush(sessionId);
      setPushStatus('disabled');
    } catch {
      setPushStatus('disable_failed');
    } finally {
      setPushBusy(false);
    }
  }

  const iosNeedsHomeScreen = isIOS() && !isStandalone();
  const pushOn = pushStatus === 'registered' || pushStatus === 'disable_failed';

  return (
    <div className="settings-screen">
      <section className="settings-section">
        <h2 className="settings-section-title">글씨 크기</h2>
        <p className="settings-section-hint">화면의 글씨와 버튼이 함께 커져요.</p>
        <div className="settings-scale-row">
          {TEXT_SCALE_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              className={`settings-scale-btn ${textScale === opt.value ? 'active' : ''}`}
              onClick={() => setTextScale(opt.value)}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </section>

      <section className="settings-section">
        <h2 className="settings-section-title">알림</h2>
        {iosNeedsHomeScreen ? (
          <p className="settings-section-hint">
            iOS에서는 이 화면을 <strong>홈 화면에 추가</strong>해야 알림을 받을 수 있어요.
            공유 버튼 → &quot;홈 화면에 추가&quot;를 눌러주세요.
          </p>
        ) : (
          <>
            <p className="settings-section-hint">
              순풍이면 오늘 첫 일정 30분 전과 마지막 일정 종료 뒤에, 주황·빨강(비·폭염·혼잡·동선)이면 감지되는 즉시 휴대폰으로 알려드려요.
            </p>
            {pushOn ? (
              <button
                type="button"
                className="btn-secondary settings-push-btn"
                onClick={handleDisablePush}
                disabled={pushBusy}
              >
                {pushBusy ? '확인 중...' : '알림 끄기'}
              </button>
            ) : (
              <button
                type="button"
                className="btn-primary settings-push-btn"
                onClick={handleEnablePush}
                disabled={pushBusy}
              >
                {pushBusy ? '확인 중...' : '알림 켜기'}
              </button>
            )}
            {PUSH_STATUS_LABEL[pushStatus] && (
              <p className="settings-section-hint settings-push-status">{PUSH_STATUS_LABEL[pushStatus]}</p>
            )}
          </>
        )}
      </section>

      <nav className="settings-menu-list" aria-label="바로가기">
        <button type="button" className="settings-menu-item" onClick={() => navigate('/my-trips')}>
          🗂️ 내 여행 관리
          <span className="settings-menu-item-chevron">›</span>
        </button>
        <button type="button" className="settings-menu-item" onClick={() => navigate('/guide')}>
          ❓ 이용 가이드
          <span className="settings-menu-item-chevron">›</span>
        </button>
      </nav>
    </div>
  );
}
