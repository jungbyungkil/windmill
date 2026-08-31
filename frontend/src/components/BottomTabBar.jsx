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
  { key: 'home', label: '홈' },
  { key: 'map', label: '지도' },
  { key: 'search', label: '검색' },
  { key: 'alerts', label: '알림' },
  { key: 'profile', label: '프로필' },
];

/**
 * 일정 화면 하단 탭 — 홈/지도/검색/알림/프로필 중 한 화면만 연다.
 */
export default function BottomTabBar({ active, onSelect }) {
  return (
    <nav className="bottom-tab-bar" aria-label="일정 화면 구간">
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
            <TabIcon name={tab.key} />
            <span className="bottom-tab-label">{tab.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
