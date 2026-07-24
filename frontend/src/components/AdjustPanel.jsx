import { useState } from 'react';

const SITUATIONS = ['갑자기 비가 옴', '관광지 혼잡도 높음', '피로감이 높음', '교통 정체', '예약 취소'];

export default function AdjustPanel({ plan, onAdjust, loading }) {
  const [situation, setSituation] = useState('');
  const [custom, setCustom] = useState('');

  function handleAdjust() {
    const actualSituation = situation || custom;
    if (!actualSituation.trim()) {
      alert('상황을 선택하거나 입력해주세요.');
      return;
    }
    onAdjust({
      destination: plan.destination,
      currentSchedule: plan.dailySchedule,
      situation: actualSituation,
      weatherCondition: plan.weather?.condition,
      date: Object.keys(plan.dailySchedule)[0],
    });
  }

  return (
    <div className="adjust-panel">
      <h3>⚡ 실시간 일정 재조정</h3>
      <p className="adjust-desc">현재 상황을 선택하면 AI가 최적의 대안 일정을 제안합니다.</p>

      <div className="situation-tags">
        {SITUATIONS.map(s => (
          <button
            key={s}
            type="button"
            className={`tag ${situation === s ? 'selected' : ''}`}
            onClick={() => { setSituation(s); setCustom(''); }}
          >
            {s}
          </button>
        ))}
      </div>

      <input
        type="text"
        placeholder="직접 입력 (예: 아이가 배고파함)"
        value={custom}
        onChange={e => { setCustom(e.target.value); setSituation(''); }}
        className="situation-input"
      />

      <button className="btn-adjust" onClick={handleAdjust} disabled={loading}>
        {loading ? '재조정 중...' : '🔄 일정 재조정'}
      </button>
    </div>
  );
}
