/** 홈(최상위) 화면 뒤로가기 - 커스텀 종료 확인. window.confirm 대신 스타일 통일용 자체 모달. */
export default function ExitConfirmModal({ open, onCancel, onConfirm }) {
  if (!open) return null;
  return (
    <div className="exit-confirm-backdrop" role="presentation" onClick={onCancel}>
      <div
        className="exit-confirm-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="exit-confirm-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="exit-confirm-title" className="exit-confirm-title">앱을 종료할까요?</h2>
        <p className="exit-confirm-message">지금까지 만든 일정은 그대로 남아있어요. 다음에 이어서 볼 수 있어요.</p>
        <div className="exit-confirm-actions">
          <button type="button" className="exit-confirm-cancel" onClick={onCancel}>취소</button>
          <button type="button" className="exit-confirm-exit" onClick={onConfirm}>종료</button>
        </div>
      </div>
    </div>
  );
}
