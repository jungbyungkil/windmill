import useModalHistory from '../hooks/useModalHistory';

/**
 * 전체 메뉴(GNB) - 좌측 슬라이드 패널. 뒤로가기(popstate)로도 닫힘(useModalHistory).
 * 알림/프로필·설정은 하단 탭바(BottomTabBar)로 이동해 여기서 뺐음(중복 진입점 방지).
 * 새 여행 시작은 일단 메뉴에서 뺐음(화면·라우트 자체는 남아있어 필요하면 바로 복원 가능).
 */
export default function GlobalMenu({
  open,
  onClose,
  onNavigateMyTrips,
  onNavigateGuide,
}) {
  useModalHistory(open, onClose);
  if (!open) return null;

  return (
    <div className="global-menu-backdrop" role="presentation" onClick={onClose}>
      <nav
        className="global-menu-panel"
        role="dialog"
        aria-modal="true"
        aria-label="전체 메뉴"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="global-menu-head">
          <span className="global-menu-title">🌬️ 바람따라</span>
          <button type="button" className="icon-btn" aria-label="메뉴 닫기" onClick={onClose}>✕</button>
        </div>
        <ul className="global-menu-list">
          <li>
            <button type="button" className="global-menu-item" onClick={onNavigateMyTrips}>
              🗂️ 내 여행 관리
            </button>
          </li>
          <li>
            <button type="button" className="global-menu-item" onClick={onNavigateGuide}>
              ❓ 이용 가이드
            </button>
          </li>
        </ul>
      </nav>
    </div>
  );
}
