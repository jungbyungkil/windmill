/**
 * 여행 바구니 진입점 - 지도·검색 화면 하단 고정 바(2026-09-15 핸드오프 브리프 9.3, 10.1).
 * 0곳이면 렌더하지 않는다.
 */
export default function TripBasketBar({ count, onOpen }) {
  if (!count) return null;
  return (
    <button type="button" className="trip-basket-bar" onClick={onOpen}>
      🧺 여행 바구니 {count}곳 보기
    </button>
  );
}
