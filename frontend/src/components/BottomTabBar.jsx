const ICON = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.85,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  viewBox: '0 0 24 24',
  className: 'bottom-tab-icon',
  'aria-hidden': true,
};

function TabIcon({ name }) {
  if (name === 'home') {
    return (
      <svg {...ICON}>
        <path d="M4 10.8 12 4l8 6.8V20a1 1 0 0 1-1 1h-5.2v-6.2H10.2V21H5a1 1 0 0 1-1-1z" />
      </svg>
    );
  }
  if (name === 'itinerary') {
    return (
      <svg {...ICON}>
        <rect x="5" y="4" width="14" height="17" rx="2" />
        <path d="M9 3.5h6a1 1 0 0 1 1 1V6H8V4.5a1 1 0 0 1 1-1z" />
        <path d="M8.5 11h7M8.5 14.5h7M8.5 18h4.5" />
      </svg>
    );
  }
  if (name === 'map') {
    return (
      <svg {...ICON}>
        <path d="M12 21s7-5.4 7-11a7 7 0 1 0-14 0c0 5.6 7 11 7 11z" />
        <circle cx="12" cy="10" r="2.2" />
      </svg>
    );
  }
  if (name === 'search') {
    return (
      <svg {...ICON}>
        <circle cx="11" cy="11" r="6.2" />
        <path d="m16 16 4.2 4.2" />
      </svg>
    );
  }
  if (name === 'alerts') {
    return (
      <svg {...ICON}>
        <path d="M6.2 9.4a5.8 5.8 0 0 1 11.6 0c0 4.2 1.4 5.4 1.4 5.4H4.8s1.4-1.2 1.4-5.4" />
        <path d="M10 19.2a2 2 0 0 0 4 0" />
      </svg>
    );
  }
  return (
    <svg {...ICON}>
      <circle cx="12" cy="8" r="3.2" />
      <path d="M5.4 19.2c.6-3.2 3.2-5 6.6-5s6 1.8 6.6 5" />
    </svg>
  );
}

const TABS = [
  // "홈" = 메인(지역/날짜/동반유형 입력 화면)으로 이동 - 2026-09-11 핸드오프 브리프: 기존엔 상단
  // "← 메인" 텍스트 버튼 하나뿐이던 진입점을 하단 탭으로 승격. 다른 5개와 달리 이 탭은 /trip 안의
  // 섹션이 아니라 라우트 자체를 벗어나는 액션이라, active로 선택되는 일은 없다(onSelect 쪽에서
  // 'main'을 특별 처리 - App.jsx의 selectTripSection 참고).
  { key: 'main', label: '홈', icon: 'home' },
  { key: 'home', label: '일정', icon: 'itinerary' },
  { key: 'map', label: '지도' },
  { key: 'search', label: '검색' },
  { key: 'alerts', label: '알림' },
  { key: 'profile', label: '프로필' },
];

/**
 * 일정 화면 하단 탭 — 홈(메인 이동)/일정/지도/검색/알림/프로필 6개.
 */
export default function BottomTabBar({ active, onSelect }) {
  return (
    <nav className="bottom-tab-bar" aria-label="하단 내비게이션">
      {TABS.map((tab) => {
        const isActive = tab.key === active;
        return (
          <button
            key={tab.key}
            type="button"
            className={`bottom-tab-btn ${isActive ? 'active' : ''}`}
            onClick={() => onSelect?.(tab.key)}
            aria-current={isActive ? 'page' : undefined}
          >
            <TabIcon name={tab.icon || tab.key} />
            <span className="bottom-tab-label">{tab.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
