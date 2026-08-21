import { useNavigate } from 'react-router-dom';

const TABS = [
  { key: 'home', label: '홈', path: '/trip' },
  { key: 'map', label: '지도', path: '/trip/map' },
  { key: 'search', label: '검색', path: '/trip/search' },
  { key: 'alerts', label: '알림', path: '/alerts' },
  { key: 'profile', label: '프로필', path: '/settings' },
];

/**
 * 5탭(홈/지도/검색/알림/프로필) 하단 고정 내비게이션 - Claude Design 핸드오프 기준.
 * 탭 전환은 스택 내비게이션이 아니라 peer 전환이라 replace:true로 히스토리를 안 쌓는다 -
 * 안 그러면 뒤로가기를 눌렀을 때 탭 사이를 튕겨다니게 됨(BackHeader의 navigate(-1)/
 * useExitConfirm의 루트 뒤로가기 가로채기와 충돌 없이 그대로 두기 위함).
 */
export default function BottomTabBar({ active }) {
  const navigate = useNavigate();
  return (
    <nav className="bottom-tab-bar" aria-label="주요 화면 전환">
      {TABS.map((tab) => {
        const isActive = tab.key === active;
        return (
          <button
            key={tab.key}
            type="button"
            className={`bottom-tab-btn ${isActive ? 'active' : ''}`}
            onClick={() => !isActive && navigate(tab.path, { replace: true })}
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
