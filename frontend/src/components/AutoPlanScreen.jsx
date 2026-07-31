import { useState } from 'react';
import { TAG_OPTIONS } from '../constants';

export default function AutoPlanScreen({ onGenerate, onConfirm, onSkip }) {
  const [tags, setTags] = useState([]);
  const [draft, setDraft] = useState(null);
  const [checked, setChecked] = useState({});
  const [generating, setGenerating] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState(null);

  function toggleTag(tag) {
    setTags((prev) => prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]);
  }

  function toggleChecked(contentId) {
    setChecked((prev) => ({ ...prev, [contentId]: !prev[contentId] }));
  }

  async function handleGenerate() {
    setGenerating(true);
    setError(null);
    try {
      const result = await onGenerate(tags);
      if (result.length === 0) {
        setError('추천할 만한 장소를 찾지 못했어요. 다른 관심사로 시도해보세요.');
        return;
      }
      setDraft(result);
      setChecked(Object.fromEntries(result.map((c) => [c.contentId, true])));
    } catch (e) {
      setError(e.message);
    } finally {
      setGenerating(false);
    }
  }

  async function handleConfirm() {
    setConfirming(true);
    try {
      await onConfirm(draft.filter((c) => checked[c.contentId]));
    } finally {
      setConfirming(false);
    }
  }

  if (!draft) {
    return (
      <div className="auto-plan-screen">
        <h2 className="section-title">AI가 일정을 짜드릴까요?</h2>
        <p className="auto-plan-desc">관심사를 골라주시면 실제 속초 관광지 중에서 하루 코스를 추천해드려요.</p>

        <div className="reco-tag-row">
          {TAG_OPTIONS.map((tag) => (
            <button
              key={tag}
              type="button"
              className={`tag ${tags.includes(tag) ? 'selected' : ''}`}
              onClick={() => toggleTag(tag)}
            >
              {tag}
            </button>
          ))}
        </div>

        <button className="btn-primary" onClick={handleGenerate} disabled={generating}>
          {generating ? 'AI가 일정 짜는 중...' : '🪄 AI가 일정 짜주기'}
        </button>

        {error && <div className="error-msg">❌ {error}</div>}

        <button className="btn-skip" onClick={onSkip}>건너뛰고 직접 담을래요</button>
      </div>
    );
  }

  return (
    <div className="auto-plan-screen">
      <h2 className="section-title">AI 추천 일정 초안</h2>
      <p className="auto-plan-desc">체크된 장소만 일정에 담겨요. 필요 없는 곳은 체크를 해제하세요.</p>

      <div className="auto-plan-draft-list">
        {draft.map((c) => (
          <label key={c.contentId} className="auto-plan-draft-item">
            <input type="checkbox" checked={!!checked[c.contentId]} onChange={() => toggleChecked(c.contentId)} />
            <span className="draft-time">{c.suggestedTime}</span>
            <span className="draft-body">
              <span className="draft-name">{c.placeName}</span>
              {c.oneLiner && <span className="draft-oneliner">{c.oneLiner}</span>}
            </span>
          </label>
        ))}
      </div>

      <button className="btn-primary" onClick={handleConfirm} disabled={confirming}>
        {confirming ? '담는 중...' : '✅ 이 일정으로 시작하기'}
      </button>

      <button className="btn-skip" onClick={onSkip}>건너뛰고 직접 담을래요</button>
    </div>
  );
}
