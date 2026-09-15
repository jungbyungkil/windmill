import useModalHistory from '../hooks/useModalHistory';
import { readContentId } from '../utils/itineraryMembership';

function formatCheckedTime(iso) {
  if (!iso) return '';
  const m = /T(\d{2}):(\d{2})/.exec(iso);
  return m ? `${m[1]}:${m[2]}` : '';
}

/**
 * 여행 바구니 바텀시트 - 목록 검토 / 개별 빼기 / 전체 비우기 / 확정(2026-09-15 핸드오프 브리프 9.3,
 * 10.1). 닫아도(✕, 배경 클릭, 뒤로가기) 바구니는 그대로 유지된다 - 닫기는 취소가 아니다(9.3).
 *
 * Phase B: 일괄 공공데이터 검증 결과(checkResults, contentId로 매칭) 표시 + 🔴 긴급 요약 배너.
 * 확정 자체를 막지는 않는다(10.4 - 긴급이어도 확정 허용, 다이얼로그는 호출부(App.jsx)가 처리).
 */
export default function TripBasketSheet({
  open,
  items = [],
  onClose,
  onRemove,
  onClearAll,
  onConfirm,
  confirming = false,
  failedContentId,
  errorMessage,
  checkResults = {},
  checking = false,
  checkedAt,
  checkFailed = false,
}) {
  useModalHistory(open, onClose);
  if (!open) return null;

  const urgentItems = items.filter((it) => checkResults[readContentId(it)]?.urgent);
  // checking 중엔 확정을 막는다 - 안 그러면 검증 결과가 아직 없는 채로(휴무·혼잡 경고 없이) 그대로
  // 담길 수 있다(2026-09-15 코드 리뷰에서 발견).
  const confirmDisabled = confirming || checking;

  return (
    <div className="trip-basket-backdrop" role="presentation" onClick={onClose}>
      <div
        className="trip-basket-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="trip-basket-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="trip-basket-head">
          <h2 id="trip-basket-title" className="trip-basket-title">여행 바구니</h2>
          <button type="button" className="trip-basket-close" aria-label="닫기" onClick={onClose}>✕</button>
        </div>

        {items.length === 0 ? (
          <p className="empty-state">아직 담은 장소가 없어요</p>
        ) : (
          <>
            <p className="trip-basket-lead">오늘 일정 끝에 순서대로 추가돼요.</p>

            {checking && (
              <p className="trip-basket-checking" role="status">
                <span className="situation-pulse" /> 영업시간·혼잡도·날씨를 확인하는 중…
              </p>
            )}
            {!checking && checkedAt && (
              <p className="trip-basket-checked">✅ 공공데이터 실시간 검증 완료 · {formatCheckedTime(checkedAt)}</p>
            )}
            {!checking && checkFailed && (
              <p className="trip-basket-check-failed">
                ⚠️ 공공데이터 검증에 실패했어요. 휴무·혼잡 여부를 확인 못 했어요.
              </p>
            )}
            {!checking && urgentItems.length > 0 && (
              <p className="trip-basket-urgent-banner">
                🔴 지금 상태가 좋지 않은 곳이 {urgentItems.length}곳 있어요
              </p>
            )}

            {errorMessage && <p className="trip-basket-error">⚠️ {errorMessage}</p>}

            <ul className="trip-basket-list">
              {items.map((item) => {
                const id = readContentId(item);
                const failed = failedContentId && id === String(failedContentId);
                const check = checkResults[id];
                return (
                  <li key={id} className={`trip-basket-item${failed ? ' is-failed' : ''}`}>
                    {item.thumbnailUrl ? (
                      <img className="trip-basket-thumb" src={item.thumbnailUrl} alt="" />
                    ) : (
                      <div className="trip-basket-thumb trip-basket-thumb-empty" aria-hidden="true">🌬️</div>
                    )}
                    <div className="trip-basket-item-body">
                      <strong className="trip-basket-item-name">{item.placeName}</strong>
                      {item.category && <span className="trip-basket-item-category">{item.category}</span>}
                      {check?.reasons?.length > 0 && (
                        <span className={`trip-basket-item-check${check.urgent ? ' is-urgent' : ''}`}>
                          {check.urgent ? '🔴' : '🟡'} {check.reasons.join(' · ')}
                        </span>
                      )}
                      {failed && <span className="trip-basket-item-failed-tag">이 장소 때문에 실패했어요</span>}
                    </div>
                    <button
                      type="button"
                      className="trip-basket-item-remove"
                      aria-label={`${item.placeName} 바구니에서 빼기`}
                      onClick={() => onRemove(item)}
                      disabled={confirming}
                    >
                      ✕
                    </button>
                  </li>
                );
              })}
            </ul>

            <div className="trip-basket-footer">
              <button
                type="button"
                className="trip-basket-clear-btn"
                onClick={onClearAll}
                disabled={confirming}
              >
                전체 비우기
              </button>
              <button
                type="button"
                className="trip-basket-confirm-btn"
                onClick={onConfirm}
                disabled={confirmDisabled}
              >
                {confirming ? '반영하는 중…' : checking ? '확인하는 중…' : `일정에 반영 (${items.length}곳)`}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
