/**
 * "OO 다녀오셨나요?" 능동 nudge - 방문 예정 시각이 지났는데 아직 완료 처리 안 된 일정이 있을 때
 * 노출한다(방문 완료 UX 개선 스펙 2항). 대상 산정·재노출(스누즈) 로직은 App.jsx가 담당하고, 이
 * 컴포넌트는 순수 표시 전용이다.
 */
export default function VisitConfirmationNudge({ item, onConfirm, onDismiss, confirming = false }) {
  if (!item) return null;
  return (
    <div className="visit-confirm-nudge" role="status">
      <p className="visit-confirm-message">{item.placeName} 다녀오셨나요?</p>
      <div className="visit-confirm-actions">
        <button
          type="button"
          className="visit-confirm-yes"
          onClick={onConfirm}
          disabled={confirming}
        >
          예, 다녀왔어요
        </button>
        <button
          type="button"
          className="visit-confirm-no"
          onClick={onDismiss}
          disabled={confirming}
        >
          아직이에요
        </button>
      </div>
    </div>
  );
}
