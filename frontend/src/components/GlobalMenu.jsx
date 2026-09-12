import useModalHistory from '../hooks/useModalHistory';
import { PinwheelIcon, TripsIcon, GuideIcon } from './Icons';

/**
 * 전체 메뉴(GNB) - 좌측 슬라이드 패널. 뒤로가기(popstate)로도 닫힘(useModalHistory).
 * 알림/프로필은 일정 한 페이지 안의 구간으로 옮겨, 여기서는 내 여행·가이드만 둔다.
 * "메인으로"는 하단 탭바의 "홈" 탭이 대체하므로 여기서는 제거함(2026-09-11 사용자 요청).
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
          <span className="global-menu-title"><PinwheelIcon size={18} /> 바람따라</span>
          <button type="button" className="icon-btn" aria-label="메뉴 닫기" onClick={onClose}>✕</button>
        </div>
        <ul className="global-menu-list">
          <li>
            <button type="button" className="global-menu-item" onClick={onNavigateMyTrips}>
              <TripsIcon size={18} /> 내 여행 관리
            </button>
          </li>
          <li>
            <button type="button" className="global-menu-item" onClick={onNavigateGuide}>
              <GuideIcon size={18} /> 이용 가이드
            </button>
          </li>
        </ul>
      </nav>
    </div>
  );
}
