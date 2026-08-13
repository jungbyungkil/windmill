import { useState } from 'react';
import { readViewHistory, clearViewHistory } from '../utils/viewHistory';

const TYPE_LABEL = { place: '장소', festival: '축제' };

function formatViewedAt(iso) {
  const d = new Date(iso);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${m}.${day} ${hh}:${mm}`;
}

/**
 * 전체 메뉴 > 이용 히스토리 - 장소/축제 조회 기록(로컬 저장, 최대 50건).
 * 이 앱엔 별도 상세 페이지가 없어(정보는 카드에 인라인 표시) 탭 시 이동 없이 목록만 보여준다.
 */
export default function HistoryScreen() {
  const [history, setHistory] = useState(readViewHistory);

  function handleClear() {
    if (!window.confirm('이용 히스토리를 모두 지울까요?')) return;
    clearViewHistory();
    setHistory([]);
  }

  return (
    <div className="history-screen">
      <div className="history-head">
        <p className="history-hint">최근 조회한 장소·축제 기록이에요 (최대 50건)</p>
        {history.length > 0 && (
          <button type="button" className="history-clear-btn" onClick={handleClear}>전체 지우기</button>
        )}
      </div>
      {history.length === 0 ? (
        <div className="empty-state">아직 조회한 기록이 없어요.</div>
      ) : (
        <ul className="history-list">
          {history.map((entry) => (
            <li key={`${entry.type}-${entry.id}`} className="history-row">
              {entry.thumbnail ? (
                <img className="history-thumb" src={entry.thumbnail} alt="" />
              ) : (
                <div className="history-thumb history-thumb-placeholder">🌬️</div>
              )}
              <div className="history-row-main">
                <span className="history-name">{entry.name}</span>
                <span className="history-meta">
                  {TYPE_LABEL[entry.type] || entry.type} · {formatViewedAt(entry.viewedAt)}
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
