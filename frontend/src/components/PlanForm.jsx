import { useState } from 'react';

const DESTINATIONS = ['서울', '부산', '제주', '경주', '강릉'];
const INTERESTS = ['자연/풍경', '문화/역사', '맛집', '쇼핑', '액티비티'];

export default function PlanForm({ onSubmit, loading }) {
  const [form, setForm] = useState({
    destination: '서울',
    startDate: new Date().toISOString().split('T')[0],
    endDate: new Date(Date.now() + 2 * 86400000).toISOString().split('T')[0],
    travelers: 2,
    interests: [],
  });

  function toggle(interest) {
    setForm(f => ({
      ...f,
      interests: f.interests.includes(interest)
        ? f.interests.filter(i => i !== interest)
        : [...f.interests, interest],
    }));
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSubmit(form);
  }

  return (
    <form className="plan-form" onSubmit={handleSubmit}>
      <h2>여행 계획 만들기</h2>

      <div className="form-group">
        <label>목적지</label>
        <select value={form.destination} onChange={e => setForm(f => ({ ...f, destination: e.target.value }))}>
          {DESTINATIONS.map(d => <option key={d}>{d}</option>)}
        </select>
      </div>

      <div className="form-row">
        <div className="form-group">
          <label>출발일</label>
          <input type="date" value={form.startDate}
            onChange={e => setForm(f => ({ ...f, startDate: e.target.value }))} />
        </div>
        <div className="form-group">
          <label>도착일</label>
          <input type="date" value={form.endDate}
            onChange={e => setForm(f => ({ ...f, endDate: e.target.value }))} />
        </div>
      </div>

      <div className="form-group">
        <label>인원 수</label>
        <input type="number" min="1" max="20" value={form.travelers}
          onChange={e => setForm(f => ({ ...f, travelers: parseInt(e.target.value) }))} />
      </div>

      <div className="form-group">
        <label>관심사</label>
        <div className="interest-tags">
          {INTERESTS.map(interest => (
            <button
              key={interest}
              type="button"
              className={`tag ${form.interests.includes(interest) ? 'selected' : ''}`}
              onClick={() => toggle(interest)}
            >
              {interest}
            </button>
          ))}
        </div>
      </div>

      <button type="submit" className="btn-primary" disabled={loading}>
        {loading ? 'AI 일정 생성 중...' : '🗺️ 일정 생성하기'}
      </button>
    </form>
  );
}
