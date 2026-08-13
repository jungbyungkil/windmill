import { useNavigate } from 'react-router-dom';

/**
 * 화면 상단 공용 헤더 - navigate(-1)로 이전 화면(브라우저 히스토리 기준)으로 이동.
 * onMenuClick을 주면 오른쪽에 전체 메뉴(☰) 버튼도 함께 노출(GNB 진입점).
 * showBack=false면 뒤로가기 버튼 없이(최상위 화면용) 자리만 맞춘다.
 */
export default function BackHeader({ title, onMenuClick, showBack = true }) {
  const navigate = useNavigate();
  return (
    <div className="back-header">
      {showBack ? (
        <button className="icon-btn back-btn" aria-label="뒤로가기" onClick={() => navigate(-1)}>←</button>
      ) : (
        <span className="back-header-spacer" aria-hidden="true" />
      )}
      {title && <span className="back-header-title">{title}</span>}
      {onMenuClick && (
        <button className="icon-btn menu-btn" aria-label="전체 메뉴" onClick={onMenuClick}>☰</button>
      )}
    </div>
  );
}
