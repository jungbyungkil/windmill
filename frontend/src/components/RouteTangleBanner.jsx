/**
 * 동선 액션 배너 - 핀휠 카드 바로 아래. 동선 꼬임(WARNING 등급)을 "액션 배너"로만 표현한다
 * (2026-09-14 핸드오프 브리프: 휴무 등 장소 단위 트리거는 카드 배지, 동선 꼬임은 일정 전체 단위라
 * 배지로 표현할 수 없어 별도 배너). 절감 시간이 10분 미만이면 매번 떠서 무시되므로 아예 숨긴다.
 */
const MIN_SAVINGS_MINUTES = 10;

export default function RouteTangleBanner({ routeTangle, onPreview, previewLoading }) {
  if (!routeTangle?.tangled) return null;
  const savings = routeTangle.savingsMinutes;
  if (savings == null || savings < MIN_SAVINGS_MINUTES) return null;

  const currentText = routeTangle.currentDurationMinutes != null
    ? `${routeTangle.currentDurationMinutes}분(${routeTangle.currentDistanceKm?.toFixed(1)}km)`
    : `${routeTangle.currentDistanceKm?.toFixed(1)}km`;
  const optimizedText = routeTangle.optimizedDurationMinutes != null
    ? `${routeTangle.optimizedDurationMinutes}분(${routeTangle.optimizedDistanceKm?.toFixed(1)}km)`
    : `${routeTangle.optimizedDistanceKm?.toFixed(1)}km`;

  return (
    <div className="route-tangle-banner">
      <div className="route-tangle-banner-text">
        <strong>동선을 정리하면 시간을 아낄 수 있어요</strong>
        <span>지금 순서로는 {currentText} → 재배치하면 {optimizedText}</span>
      </div>
      <button
        type="button"
        className="route-tangle-banner-btn"
        onClick={onPreview}
        disabled={previewLoading}
      >
        {previewLoading ? '불러오는 중...' : '재배치 미리보기'}
      </button>
    </div>
  );
}
