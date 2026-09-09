import { useState } from 'react';
import useModalHistory from '../hooks/useModalHistory';
import { formatRelativeTime } from './AlertFeedScreen';
import BaramiBubble from './BaramiBubble';

const TRIGGER_ICON = {
  WEATHER: '🌧️',
  HEAT: '🌡️',
  CROWD: '🌊',
  ROUTE: '🧭',
  REVERT: '↩️',
  MANUAL: '✏️',
};

function stopsSummary(snapshot) {
  const stops = snapshot?.stops || [];
  if (stops.length === 0) return '담긴 장소 없음';
  return stops.map((s) => s.placeName).filter(Boolean).join(' · ');
}

/** 이력 카드에 붙는 "바람이" 한마디 - triggerType 기준 톤 있는 코멘트 */
function baramiComment(entry) {
  const place = entry.changedPlaceName;
  switch (entry.triggerType) {
    case 'WEATHER':
      return place ? `비 소식이 있어서 ${place}(으)로 바꿨어요!` : '비 소식이 있어서 실내 코스로 바꿨어요!';
    case 'HEAT':
      return place ? `너무 더워서 ${place}(으)로 바꿨어요!` : '너무 더워서 실내 코스로 바꿨어요!';
    case 'CROWD':
      return place ? `사람이 많아서 한산한 ${place}(으)로 바꿨어요!` : '사람이 많은 곳 대신 한산한 데로 바꿨어요!';
    case 'ROUTE':
      return '덜 걷도록 동선을 다시 짰어요!';
    case 'REVERT':
      return '말씀대로 예전 일정으로 되돌렸어요!';
    default:
      return place ? `${place} 반영해서 일정을 다시 맞췄어요!` : '일정을 직접 정리했어요!';
  }
}

/**
 * 대안 일정 "원본 + 변경 이력" 패널.
 * - 원본(Plan A) 카드는 항상 맨 위 고정
 * - 변경 이력 #N은 최신이 위로
 * - 현재 적용 중인 항목에 강조 표시, 그 외 항목은 "이 안으로 되돌리기"(확인 후 적용)
 */
export default function PlanHistoryPanel({
  open,
  originalPlan,
  changeHistory = [],
  reverting = false,
  onRevert,
  onClose,
}) {
  useModalHistory(open, onClose);
  const [confirmTarget, setConfirmTarget] = useState(undefined); // undefined=닫힘, null=원본, number=sequence

  if (!open) return null;

  const history = [...changeHistory];
  const currentSequence = history.length > 0 ? history[history.length - 1].sequence : null;
  const reversed = [...history].reverse();

  function askRevert(target) {
    setConfirmTarget(target);
  }
  async function doRevert() {
    const target = confirmTarget;
    setConfirmTarget(undefined);
    await onRevert(target ?? null);
  }

  const confirmLabel = confirmTarget == null ? '원본' : `변경 이력 #${confirmTarget}`;

  return (
    <div className="plan-history-backdrop" role="presentation" onClick={onClose}>
      <div
        className="plan-history-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="plan-history-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="plan-history-head">
          <h2 id="plan-history-title" className="plan-history-title">일정 변경 이력</h2>
          <button type="button" className="plan-history-close" aria-label="닫기" onClick={onClose}>✕</button>
        </div>
        <p className="plan-history-lead">
          비·폭염·혼잡·동선 변수로 바뀐 기록이에요. 원본이나 지난 시점으로 되돌릴 수 있어요.
        </p>

        <ol className="plan-history-list">
          {/* 원본 - 항상 맨 위 고정 */}
          <li className={`plan-history-item is-original${currentSequence == null ? ' is-current' : ''}`}>
            <div className="plan-history-item-head">
              <span className="plan-history-badge original">📌 원본</span>
              {currentSequence == null && <span className="plan-history-current-tag">현재 적용 중</span>}
            </div>
            <p className="plan-history-stops">{stopsSummary(originalPlan)}</p>
            <BaramiBubble compact comment="이게 맨 처음 짠 일정이에요." />
            {currentSequence != null && (
              <button
                type="button"
                className="plan-history-revert-btn"
                disabled={reverting}
                onClick={() => askRevert(null)}
              >
                원본으로 되돌리기
              </button>
            )}
          </li>

          {reversed.map((entry) => {
            const isCurrent = entry.sequence === currentSequence;
            return (
              <li
                key={entry.sequence}
                className={`plan-history-item${isCurrent ? ' is-current' : ''}`}
              >
                <div className="plan-history-item-head">
                  <span className="plan-history-badge">
                    {TRIGGER_ICON[entry.triggerType] || '✏️'} 변경 이력 #{entry.sequence}
                  </span>
                  <span className="plan-history-time">{formatRelativeTime(entry.changedAt)}</span>
                  {isCurrent && <span className="plan-history-current-tag">현재 적용 중</span>}
                </div>
                {entry.reason && <p className="plan-history-reason">{entry.reason}</p>}
                {entry.changedPlaceName && (
                  <p className="plan-history-changed">→ {entry.changedPlaceName}</p>
                )}
                <p className="plan-history-stops">{stopsSummary(entry.snapshot)}</p>
                <BaramiBubble compact comment={baramiComment(entry)} />
                {!isCurrent && (
                  <button
                    type="button"
                    className="plan-history-revert-btn"
                    disabled={reverting}
                    onClick={() => askRevert(entry.sequence)}
                  >
                    이 안으로 되돌리기
                  </button>
                )}
              </li>
            );
          })}
        </ol>

        {confirmTarget !== undefined && (
          <div className="plan-history-confirm" role="alertdialog" aria-label="되돌리기 확인">
            <p className="plan-history-confirm-msg">
              지금 일정을 <strong>{confirmLabel}</strong> 상태로 되돌릴까요?
              <br />
              되돌리기도 이력에 남고, 다시 앞으로 되돌아올 수 있어요.
            </p>
            <div className="plan-history-confirm-actions">
              <button
                type="button"
                className="plan-history-confirm-cancel"
                disabled={reverting}
                onClick={() => setConfirmTarget(undefined)}
              >
                취소
              </button>
              <button
                type="button"
                className="plan-history-confirm-ok"
                disabled={reverting}
                onClick={doRevert}
              >
                {reverting ? '되돌리는 중…' : '되돌리기'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
