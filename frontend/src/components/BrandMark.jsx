/**
 * 바람따라 대표 이미지 - 도는 바람개비 + 워드마크를 가로로 묶은 브랜드 락업.
 * 메인(당일치기 생성) 화면 최상단에 놓는다. 바람개비 SVG는 PinwheelHero와 같은 형태.
 */
export default function BrandMark() {
  return (
    <div className="brand-lockup">
      <svg viewBox="0 0 200 200" className="brand-lockup-pinwheel" aria-hidden="true">
        <g className="brand-lockup-blades">
          {[0, 90, 180, 270].map((deg) => (
            <path
              key={deg}
              className="brand-lockup-blade"
              transform={`rotate(${deg} 100 100)`}
              d="M100,100 C100,60 120,30 155,25 C160,55 145,85 100,100 Z"
            />
          ))}
        </g>
        <circle className="brand-lockup-hub" cx="100" cy="100" r="10" />
      </svg>
      <span className="brand-lockup-name">바람따라</span>
    </div>
  );
}
