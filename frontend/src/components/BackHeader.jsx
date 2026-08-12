import { useNavigate } from 'react-router-dom';

/** 화면 상단 공용 뒤로가기 헤더 - navigate(-1)로 이전 화면(브라우저 히스토리 기준)으로 이동 */
export default function BackHeader({ title }) {
  const navigate = useNavigate();
  return (
    <div className="back-header">
      <button className="icon-btn back-btn" aria-label="뒤로가기" onClick={() => navigate(-1)}>←</button>
      {title && <span className="back-header-title">{title}</span>}
    </div>
  );
}
