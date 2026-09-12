/**
 * 바람따라 (Baramttara) — 아이콘 세트 (2026-09-12 아이콘 추천안 반영)
 *
 * 설계 규칙
 *  - 24×24 그리드, 라이브 영역 20×20 (상하좌우 2px 여백)
 *  - stroke 1.75 / round cap / round join
 *  - fill="none", stroke="currentColor" → CSS color 로 제어 (상태색·다크모드 대응)
 *  - 모티프: 바람개비 날개의 "쓸어나가는 곡선"을 전체 세트에서 반복
 *
 * 사용법
 *   import { PinwheelIcon, TripsIcon, TrashIcon } from './Icons';
 *   <TrashIcon className="text-red-500" size={20} />
 *
 * 접근성
 *   장식용(옆에 텍스트 라벨 있음) → 기본값 aria-hidden 유지
 *   아이콘만 있는 버튼      → <PencilIcon label="수정" /> 로 접근명 부여
 */

const Icon = ({
  size = 24,
  strokeWidth = 1.75,
  label,
  className = '',
  children,
  ...rest
}) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={strokeWidth}
    strokeLinecap="round"
    strokeLinejoin="round"
    role={label ? 'img' : undefined}
    aria-label={label}
    aria-hidden={label ? undefined : true}
    focusable="false"
    // bt-icon: 텍스트 옆에 인라인으로 놓일 때 베이스라인을 맞추는 공통 클래스(App.css)
    className={className ? `bt-icon ${className}` : 'bt-icon'}
    {...rest}
  >
    {label ? <title>{label}</title> : null}
    {children}
  </svg>
);

/* ── 브랜드 ───────────────────────────────────────────── */

// 바람따라 로고 / 핀휠 상태 배지 - spinning은 기존 pinwheel-spin 키프레임(App.css)을 재사용한다.
export const PinwheelIcon = ({ spinning = false, ...p }) => (
  <Icon {...p}>
    <g
      className={spinning ? 'bt-icon-spin' : undefined}
      fill="currentColor"
      stroke="none"
      style={spinning ? { transformOrigin: '12px 12px' } : undefined}
    >
      <path d="M12 12C12 6.6 15 3.2 19 3.2C19 7.2 15.6 12 12 12Z" />
      <path d="M12 12C12 6.6 15 3.2 19 3.2C19 7.2 15.6 12 12 12Z" transform="rotate(90 12 12)" />
      <path d="M12 12C12 6.6 15 3.2 19 3.2C19 7.2 15.6 12 12 12Z" transform="rotate(180 12 12)" />
      <path d="M12 12C12 6.6 15 3.2 19 3.2C19 7.2 15.6 12 12 12Z" transform="rotate(270 12 12)" />
    </g>
    <circle cx="12" cy="12" r="2.1" fill="var(--bt-surface, #fff)" stroke="none" />
    <circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none" />
  </Icon>
);

/* ── 전역 내비게이션 ───────────────────────────────────── */

// 내 여행 관리 — 저장된 일정(경로 위의 두 지점)
export const TripsIcon = (p) => (
  <Icon {...p}>
    <path d="M7 3.2c-2 0-3.6 1.7-3.6 3.7 0 2.7 3.6 5.6 3.6 5.6s3.6-2.9 3.6-5.6c0-2-1.6-3.7-3.6-3.7Z" />
    <circle cx="7" cy="6.9" r="1.25" />
    <path d="M17.2 11.5c-1.8 0-3.2 1.5-3.2 3.3 0 2.4 3.2 5 3.2 5s3.2-2.6 3.2-5c0-1.8-1.4-3.3-3.2-3.3Z" />
    <path d="M10.2 10.6c3.1.9 2.4 4.3 4.4 5.4" strokeDasharray="0.1 2.6" />
  </Icon>
);

// 이용 가이드
export const GuideIcon = (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="8.6" />
    <path d="M9.5 9.6a2.6 2.6 0 0 1 5 .9c0 1.7-2.5 2.6-2.5 2.6" />
    <path d="M12 16.6h.01" strokeWidth="2.2" />
  </Icon>
);

/* ── 코스 선택 분기 ────────────────────────────────────── */

// 추천 코스 — 바람이 만들어주는 코스
export const RecommendIcon = (p) => (
  <Icon {...p}>
    <path d="M10 3.6q.9 5.4 6 6.2-5.1.8-6 6.2-.9-5.4-6-6.2 5.1-.8 6-6.2Z" />
    <path d="M17.6 14.2q.4 2.6 2.9 3-2.5.4-2.9 3-.4-2.6-2.9-3 2.5-.4 2.9-3Z" />
  </Icon>
);

// 직접 선택 — 내가 고르는 코스
export const PickIcon = (p) => (
  <Icon {...p}>
    <path d="M6.6 3.9 6.6 17.2 9.9 14 12 19.1 14.4 18.1 12.3 13.1 16.9 12.7Z" />
  </Icon>
);

/* ── 일정 카드 액션 ────────────────────────────────────── */

// 되돌리기
export const UndoIcon = (p) => (
  <Icon {...p}>
    <path d="M3.9 8.6 7.6 4.9M3.9 8.6l3.7 3.7" />
    <path d="M3.9 8.6h9.8a5.3 5.3 0 0 1 0 10.6H9.8" />
  </Icon>
);

// 수정
export const PencilIcon = (p) => (
  <Icon {...p}>
    <path d="M16.8 3.9a2.2 2.2 0 0 1 3.1 3.1L8.4 18.5l-4.3 1.2 1.2-4.3Z" />
    <path d="M15.2 5.5l3.1 3.1" />
  </Icon>
);

// 도슨트 (오디오 해설)
export const DocentIcon = (p) => (
  <Icon {...p}>
    <path d="M4.4 14.8v-2.5a7.6 7.6 0 0 1 15.2 0v2.5" />
    <path d="M4.4 14.6h1.9a1.5 1.5 0 0 1 1.5 1.5v2.6a1.5 1.5 0 0 1-1.5 1.5h-.6a1.3 1.3 0 0 1-1.3-1.3Z" />
    <path d="M19.6 14.6h-1.9a1.5 1.5 0 0 0-1.5 1.5v2.6a1.5 1.5 0 0 0 1.5 1.5h.6a1.3 1.3 0 0 0 1.3-1.3Z" />
  </Icon>
);

// 삭제
export const TrashIcon = (p) => (
  <Icon {...p}>
    <path d="M4.4 6.4h15.2" />
    <path d="M9.4 6.4V4.8a1.3 1.3 0 0 1 1.3-1.3h2.6a1.3 1.3 0 0 1 1.3 1.3v1.6" />
    <path d="M6.6 6.4l.86 12.3a1.6 1.6 0 0 0 1.6 1.5h5.88a1.6 1.6 0 0 0 1.6-1.5l.86-12.3" />
    <path d="M10.2 10.2v6M13.8 10.2v6" />
  </Icon>
);

// 다녀옴
export const VisitedIcon = ({ filled = false, ...p }) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="8.6" fill={filled ? 'currentColor' : 'none'} />
    <path
      d="M8.2 12.3l2.7 2.7 5.1-5.4"
      stroke={filled ? 'var(--bt-surface, #fff)' : 'currentColor'}
    />
  </Icon>
);

/* ── 핀휠 상태 트리거 ──────────────────────────────────── */

// 혼잡
export const CrowdIcon = (p) => (
  <Icon {...p}>
    <circle cx="9.3" cy="8.1" r="3.2" />
    <path d="M3.3 19.6a6 6 0 0 1 12 0" />
    <circle cx="17.6" cy="9.4" r="2.3" />
    <path d="M16.2 14a4.7 4.7 0 0 1 4.5 4.7" />
  </Icon>
);

// 폭염
export const HeatIcon = (p) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="3.9" />
    <path d="M12 3.4v1.9M12 18.7v1.9M3.4 12h1.9M18.7 12h1.9M5.9 5.9l1.4 1.4M16.7 16.7l1.4 1.4M18.1 5.9l-1.4 1.4M7.3 16.7l-1.4 1.4" />
  </Icon>
);

// 우천
export const RainIcon = (p) => (
  <Icon {...p}>
    <path d="M6.8 15.4a3.6 3.6 0 0 1 .5-7.2 5 5 0 0 1 9.5 1.1 3.4 3.4 0 0 1 .2 6.1" />
    <path d="M6.8 15.4h10.2" />
    <path d="M8.6 18.2l-1 2.4M12 18.2l-1 2.4M15.4 18.2l-1 2.4" />
  </Icon>
);
