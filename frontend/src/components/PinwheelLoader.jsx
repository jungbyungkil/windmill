/**
 * 당일치기 시작하기를 누른 뒤 - 앵커/스마트 일정이 채워지고 동선까지 잡힐 때까지
 * 브랜드 바람개비가 빠르게 도는 전체 화면 로딩.
 */
export default function PinwheelLoader({ message = '동선을 찾고 있어요...' }) {
  return (
    <div className="pinwheel-loading-overlay" role="status" aria-live="polite">
      <svg viewBox="0 0 200 200" className="pinwheel-loading-svg" aria-hidden="true">
        <g className="pinwheel-loading-blades">
          {[0, 90, 180, 270].map((deg) => (
            <path
              key={deg}
              className="pinwheel-loading-blade"
              transform={`rotate(${deg} 100 100)`}
              d="M100,100 C100,60 120,30 155,25 C160,55 145,85 100,100 Z"
            />
          ))}
        </g>
        <circle className="pinwheel-loading-hub" cx="100" cy="100" r="10" />
      </svg>
      <p className="pinwheel-loading-message">{message}</p>
    </div>
  );
}
