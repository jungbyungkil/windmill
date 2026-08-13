import { useState } from 'react';
import useTextScale from '../hooks/useTextScale';
import { isIOS, isStandalone, isPushSupported, requestPushToken } from '../utils/webPush';
import { registerPush } from '../api/windmillApi';

const TEXT_SCALE_OPTIONS = [
  { value: 'default', label: '기본' },
  { value: 'large', label: '크게' },
  { value: 'xl', label: '아주 크게' },
];

const PUSH_STATUS_LABEL = {
  denied: '브라우저에서 알림이 차단돼 있어요. 브라우저 설정에서 허용해 주세요.',
  unsupported: '이 브라우저·환경은 웹 푸시를 지원하지 않아요.',
  granted: '알림이 켜졌어요. 서비스 준비가 끝나는 대로 실시간 변수 알림을 보내드릴게요.',
  registered: '알림이 켜졌어요.',
};

/** 전체 메뉴 > 설정 - 글씨 크기(어르신 접근성), 알림(웹 푸시) */
export default function SettingsScreen({ sessionId }) {
  const [textScale, setTextScale] = useTextScale();
  const [pushStatus, setPushStatus] = useState('idle');

  async function handleEnablePush() {
    if (!isPushSupported()) {
      setPushStatus('unsupported');
      return;
    }
    setPushStatus('requesting');
    const token = await requestPushToken();
    if (Notification.permission === 'denied') {
      setPushStatus('denied');
      return;
    }
    if (token) {
      await registerPush(sessionId, { fcmToken: token });
      setPushStatus('registered');
    } else {
      setPushStatus('granted');
    }
  }

  const iosNeedsHomeScreen = isIOS() && !isStandalone();

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
              비·폭염·혼잡 같은 실시간 변수가 생기면 알려드려요.
            </p>
            <button type="button" className="btn-primary settings-push-btn" onClick={handleEnablePush} disabled={pushStatus === 'requesting'}>
              {pushStatus === 'requesting' ? '확인 중...' : '알림 켜기'}
            </button>
            {PUSH_STATUS_LABEL[pushStatus] && (
              <p className="settings-section-hint settings-push-status">{PUSH_STATUS_LABEL[pushStatus]}</p>
            )}
          </>
        )}
      </section>
    </div>
  );
}
