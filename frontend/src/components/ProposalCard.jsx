const TRIGGER_LABEL = {
  ROUTE: '동선',
  CROWD: '혼잡',
  WEATHER: '날씨',
  HEAT: '날씨',
  CLOSED: '휴무',
};

/**
 * 승인 대기 중인 자동 변경 제안 카드(2026-09-15 핸드오프 브리프: 동선 변경 승인제 전환).
 * 누지 카드 영역 최상단에 노출 - 세션당 최대 1건, 사용자가 "일정에 적용"을 눌러야만 순서가 바뀐다.
 */
export default function ProposalCard({ proposal, onApply, onDismiss, applying, dismissing }) {
  if (!proposal) return null;

  const before = proposal.before?.[0];
  const after = proposal.after?.[0];
  const busy = applying || dismissing;

  return (
    <div className="proposal-card" role="status">
      <div className="proposal-card-head">
        <span className="proposal-card-tag">{TRIGGER_LABEL[proposal.trigger] || '제안'}</span>
        <strong className="proposal-card-reason">{proposal.reason}</strong>
      </div>

      {proposal.evidence?.length > 0 && (
        <div className="proposal-card-evidence">
          {proposal.evidence.map((e, i) => (
            <span key={`${e.source}-${i}`} className="proposal-evidence-badge">
              {e.label} {e.value} · 공공데이터 실시간 확인
            </span>
          ))}
        </div>
      )}

      {before && after && (
        <div className="proposal-card-preview">
          {before.scheduledTime && `${before.scheduledTime} `}{before.placeName}
          <span className="proposal-card-arrow"> → </span>
          {after.scheduledTime && `${after.scheduledTime} `}{after.placeName}
        </div>
      )}

      <div className="proposal-card-actions">
        <button
          type="button"
          className="btn-proposal-apply"
          onClick={onApply}
          disabled={busy}
        >
          {applying ? '적용하는 중...' : '일정에 적용'}
        </button>
        <button
          type="button"
          className="btn-proposal-dismiss"
          onClick={onDismiss}
          disabled={busy}
        >
          {dismissing ? '처리하는 중...' : '괜찮아요'}
        </button>
      </div>
    </div>
  );
}
