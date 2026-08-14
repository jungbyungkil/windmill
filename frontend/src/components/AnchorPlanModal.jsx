import { useEffect, useState } from 'react';
import useModalHistory from '../hooks/useModalHistory';

/**
 * 앵커(고정 일정) 등록 - "가고 싶은 곳 검색해서 찾기"에서 장소를 고르면 바로 담는 대신
 * 이 모달을 거친다: 공연/이벤트 시작 시각을 정하면, 그 앞뒤 빈 시간대(점심·가벼운 도보 관광 /
 * 저녁 식사·카페)를 관광공사 연관 관광지(TarRlteTarService1) 데이터로 채운 초안을 보여주고,
 * 체크된 곳만 일정에 담는다.
 */
export default function AnchorPlanModal({ open, anchor, onGenerate, onConfirm, onClose }) {
  const [step, setStep] = useState('setup');
  const [anchorTime, setAnchorTime] = useState('19:00');
  const [draft, setDraft] = useState(null);
  const [checked, setChecked] = useState({});
  const [generating, setGenerating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState(null);

  useModalHistory(open, onClose);

  useEffect(() => {
    if (open) {
      setStep('setup');
      setAnchorTime('19:00');
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
    if (!anchorTime) {
      setError('시작 시각을 선택해 주세요.');
      return;
    }
    setGenerating(true);
    setError(null);
    try {
      const result = await onGenerate(anchor, anchorTime);
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
            <p className="anchor-plan-eyebrow">고정 일정(앵커) 등록</p>
            <h2 id="anchor-plan-title" className="anchor-plan-title">{anchor.placeName}</h2>
            <p className="anchor-plan-message">
              공연·전시 등 시작 시각을 정해주시면, 그 앞뒤 빈 시간(점심·가벼운 도보 관광 / 저녁 식사·카페)을
              이 장소 주변 연관 관광지 데이터로 자동으로 채워드려요.
            </p>

            <div className="anchor-plan-field">
              <span className="anchor-plan-field-label">시작 시각</span>
              <input
                type="time"
                className="anchor-plan-time-input"
                value={anchorTime}
                onChange={(e) => setAnchorTime(e.target.value)}
              />
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
            <p className="anchor-plan-eyebrow">{anchor.placeName} · {anchorTime} 시작</p>
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
