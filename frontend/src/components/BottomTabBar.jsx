const TABS = [
  { key: 'home', label: '홈' },
  { key: 'map', label: '지도' },
  { key: 'search', label: '검색' },
  { key: 'alerts', label: '알림' },
  { key: 'profile', label: '프로필' },
];

/**
 * 일정 한 페이지 안의 구간(홈/지도/검색/알림/프로필)으로 이동하는 하단 바.
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
            <span className="bottom-tab-dot" aria-hidden="true" />
            <span className="bottom-tab-label">{tab.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
