import useModalHistory from '../hooks/useModalHistory';

/** 전체 메뉴(GNB) - 좌측 슬라이드 패널. 뒤로가기(popstate)로도 닫힘(useModalHistory). */
export default function GlobalMenu({ open, onClose, onNavigateMyTrips, onNavigateHome }) {
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
            <button type="button" className="global-menu-item" onClick={onNavigateHome}>
              ➕ 새 여행 시작
            </button>
          </li>
        </ul>
      </nav>
    </div>
  );
}
