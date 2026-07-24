import { useState } from 'react';
import PlanForm from './components/PlanForm';
import WeatherBanner from './components/WeatherBanner';
import ScheduleCard from './components/ScheduleCard';
import AdjustPanel from './components/AdjustPanel';
import { createSchedule, adjustSchedule } from './api/travelApi';
import './App.css';

export default function App() {
  const [plan, setPlan] = useState(null);
  const [loading, setLoading] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const [error, setError] = useState(null);
  const [adjustLog, setAdjustLog] = useState([]);

  async function handleCreate(formData) {
    setLoading(true);
    setError(null);
    try {
      const result = await createSchedule(formData);
      setPlan(result);
      setAdjustLog([]);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleAdjust(adjustData) {
    setAdjusting(true);
    setError(null);
    try {
      const result = await adjustSchedule(adjustData);
      setAdjustLog(prev => [...prev, adjustData.situation]);
      setPlan(prev => ({ ...prev, dailySchedule: result.dailySchedule, weather: result.weather }));
    } catch (e) {
      setError(e.message);
    } finally {
      setAdjusting(false);
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-inner">
          <span className="logo">🌀 우리 여기로 가자</span>
          <span className="tagline">실시간 변수 대응 여행 일정 AI</span>
        </div>
      </header>

      <main className="app-main">
        {!plan ? (
          <div className="form-section">
            <PlanForm onSubmit={handleCreate} loading={loading} />
          </div>
        ) : (
          <div className="result-section">
            <div className="result-header">
              <button className="btn-back" onClick={() => setPlan(null)}>← 새 여행 계획</button>
              <h2>{plan.destination} 여행 일정</h2>
              <span className="date-range">{plan.startDate} ~ {plan.endDate} · {plan.travelers}명</span>
            </div>

            {plan.weather && <WeatherBanner weather={plan.weather} />}

            {plan.aiSummary && (
              <div className="ai-summary">
                <span className="ai-badge">AI</span>
                {plan.aiSummary}
              </div>
            )}

            {adjustLog.length > 0 && (
              <div className="adjust-log">
                <strong>재조정 이력:</strong>
                {adjustLog.map((log, i) => <span key={i} className="log-item">#{i + 1} {log}</span>)}
              </div>
            )}

            <AdjustPanel plan={plan} onAdjust={handleAdjust} loading={adjusting} />

            <div className="schedules">
              {Object.entries(plan.dailySchedule).map(([date, items]) => (
                <ScheduleCard key={date} date={date} items={items} />
              ))}
            </div>
          </div>
        )}

        {error && <div className="error-msg">❌ {error}</div>}
      </main>

      <footer className="app-footer">
        <p>우리 여기로 가자 POC v0.1 · Claude AI 기반 실시간 여행 일정 재조정</p>
      </footer>
    </div>
  );
}
