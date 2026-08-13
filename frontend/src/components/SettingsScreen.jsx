import useTextScale from '../hooks/useTextScale';

const TEXT_SCALE_OPTIONS = [
  { value: 'default', label: '기본' },
  { value: 'large', label: '크게' },
  { value: 'xl', label: '아주 크게' },
];

/** 전체 메뉴 > 설정 - 글씨 크기(어르신 접근성) 등 */
export default function SettingsScreen() {
  const [textScale, setTextScale] = useTextScale();

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
        <p className="settings-section-hint">웹 푸시 알림 설정은 곧 추가돼요.</p>
      </section>
    </div>
  );
}
