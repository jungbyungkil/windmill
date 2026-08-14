import { useEffect, useState } from 'react';
import useModalHistory from '../hooks/useModalHistory';

const DURATION_OPTIONS = [
  { label: '30분', minutes: 30 },
  { label: '1시간', minutes: 60 },
  { label: '1시간 30분', minutes: 90 },
  { label: '2시간', minutes: 120 },
  { label: '3시간', minutes: 180 },
];

/**
 * 목적지 직접 선택 플로우 - "가고 싶은 곳 검색해서 담기"에서 장소를 고르면 바로 담는 대신
 * 이 모달을 거친다: 체류시간 + 오전/오후 배치를 정하면, 그 앞뒤 빈 시간대(점심·주변 전시관/박물관·
 * 저녁 식사/카페)를 자동으로 채운 초안을 보여주고, 체크된 곳만 일정에 담는다.
 */
export default function AnchorPlanModal({ open, anchor, onGenerate, onConfirm, onClose }) {
  const [step, setStep] = useState('setup');
  const [durationMinutes, setDurationMinutes] = useState(120);
  const [slot, setSlot] = useState('MORNING');
  const [draft, setDraft] = useState(null);
  const [checked, setChecked] = useState({});
  const [generating, setGenerating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState(null);

  useModalHistory(open, onClose);

  useEffect(() => {
    if (open) {
      setStep('setup');
      setDurationMinutes(120);
      setSlot('MORNING');
      setDraft(null);
      setChecked({});
      setError(null);
    }
  }, [open, anchor?.contentId]);

  if (!open || !anchor) return null;

  function toggleChecked(contentId) {
    setChecked((prev) => ({ ...prev, [contentId]: !prev[contentId] }));
  }

  async function handleGenerate() {
    setGenerating(true);
    setError(null);
    try {
      const result = await onGenerate(anchor, durationMinutes, slot);
      setDraft(result);
      setChecked(Object.fromEntries(result.map((c) => [c.contentId, true])));
      setStep('review');
    } catch (e) {
      setError(e.message || '일정을 채우지 못했어요.');
    } finally {
      setGenerating(false);
    }
  }

  async function handleConfirm() {
    setConfirming(true);
    try {
      await onConfirm(draft.filter((c) => checked[c.contentId]));
      onClose();
    } finally {
      setConfirming(false);
    }
  }

  return (
    <div className="anchor-plan-backdrop" role="presentation" onClick={onClose}>
      <div
        className="anchor-plan-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="anchor-plan-title"
        onClick={(e) => e.stopPropagation()}
      >
        {step === 'setup' ? (
          <>
            <p className="anchor-plan-eyebrow">목적지 직접 선택</p>
            <h2 id="anchor-plan-title" className="anchor-plan-title">{anchor.placeName}</h2>
            <p className="anchor-plan-message">
              얼마나 머무를지, 오전/오후 중 언제 갈지 정해주시면 나머지 빈 시간(점심·전시관/박물관·저녁)을
              주변에서 자동으로 채워드려요.
            </p>

            <div className="anchor-plan-field">
              <span className="anchor-plan-field-label">체류시간</span>
              <div className="anchor-plan-duration-row">
                {DURATION_OPTIONS.map((opt) => (
                  <button
                    key={opt.minutes}
                    type="button"
                    className={`anchor-plan-chip ${durationMinutes === opt.minutes ? 'selected' : ''}`}
                    onClick={() => setDurationMinutes(opt.minutes)}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="anchor-plan-field">
              <span className="anchor-plan-field-label">배치</span>
              <div className="anchor-plan-slot-row">
                <button
                  type="button"
                  className={`anchor-plan-chip ${slot === 'MORNING' ? 'selected' : ''}`}
                  onClick={() => setSlot('MORNING')}
                >
                  오전
                </button>
                <button
                  type="button"
                  className={`anchor-plan-chip ${slot === 'AFTERNOON' ? 'selected' : ''}`}
                  onClick={() => setSlot('AFTERNOON')}
                >
                  오후
                </button>
              </div>
            </div>

            {error && <div className="error-msg">❌ {error}</div>}

            <div className="anchor-plan-actions">
              <button className="btn-primary" onClick={handleGenerate} disabled={generating}>
                {generating ? '주변 채우는 중...' : '🪄 빈 시간 채우기'}
              </button>
              <button className="btn-skip" onClick={onClose}>취소</button>
            </div>
          </>
        ) : (
          <>
            <p className="anchor-plan-eyebrow">{anchor.placeName} · {slot === 'MORNING' ? '오전 배치' : '오후 배치'}</p>
            <h2 id="anchor-plan-title" className="anchor-plan-title">채워진 일정 초안</h2>
            <p className="anchor-plan-message">체크된 장소만 일정에 담겨요.</p>

            <div className="auto-plan-draft-list anchor-plan-draft-list">
              {draft.map((c) => (
                <label key={c.contentId} className="auto-plan-draft-item">
                  <input type="checkbox" checked={!!checked[c.contentId]} onChange={() => toggleChecked(c.contentId)} />
                  {c.thumbnailUrl ? (
                    <img className="item-thumb" src={c.thumbnailUrl} alt={c.placeName} loading="lazy" />
                  ) : (
                    <div className="item-thumb item-thumb-placeholder">🌬️</div>
                  )}
                  <span className="draft-time">{c.suggestedTime}</span>
                  <span className="draft-body">
                    <span className="draft-name">{c.placeName}</span>
                    {c.oneLiner && <span className="draft-oneliner">{c.oneLiner}</span>}
                  </span>
                </label>
              ))}
            </div>

            <div className="anchor-plan-actions">
              <button className="btn-primary" onClick={handleConfirm} disabled={confirming}>
                {confirming ? '담는 중...' : '✅ 이 일정으로 담기'}
              </button>
              <button className="btn-skip" onClick={() => setStep('setup')} disabled={confirming}>다시 설정하기</button>
              <button className="btn-skip" onClick={onClose} disabled={confirming}>취소</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
