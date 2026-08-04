function enumerateDates(startDate, endDate) {
  const dates = [];
  const cursor = new Date(startDate + 'T00:00:00');
  const last = new Date(endDate + 'T00:00:00');
  while (cursor <= last) {
    dates.push(cursor.toISOString().slice(0, 10));
    cursor.setDate(cursor.getDate() + 1);
  }
  return dates;
}

function formatDay(dateStr, dayIndex) {
  const d = new Date(dateStr + 'T00:00:00');
  const md = `${d.getMonth() + 1}/${d.getDate()}`;
  return `${dayIndex + 1}일차 (${md})`;
}

export default function DayTabs({ startDate, endDate, activeDate, confirmedDates, onSelectDate, onToggleConfirm, confirming }) {
  const dates = enumerateDates(startDate, endDate);
  if (dates.length <= 1) return null; // 당일치기면 탭 없이 기존 단일 목록 그대로

  const activeIndex = dates.indexOf(activeDate);
  const isActiveConfirmed = confirmedDates?.includes(activeDate);
  const nextDate = activeIndex >= 0 && activeIndex < dates.length - 1 ? dates[activeIndex + 1] : null;

  return (
    <div className="day-tabs">
      <div className="day-tabs-row">
        {dates.map((date, i) => (
          <button
            key={date}
            type="button"
            className={`day-tab ${date === activeDate ? 'active' : ''} ${confirmedDates?.includes(date) ? 'confirmed' : ''}`}
            onClick={() => onSelectDate(date)}
          >
            {confirmedDates?.includes(date) && <span className="day-tab-check">✅</span>}
            {formatDay(date, i)}
          </button>
        ))}
      </div>

      <div className="day-tabs-actions">
        <button
          type="button"
          className={`btn-day-confirm ${isActiveConfirmed ? 'confirmed' : ''}`}
          onClick={() => onToggleConfirm(activeDate, !isActiveConfirmed)}
          disabled={confirming}
        >
          {isActiveConfirmed ? '✅ 이 날 일정 확정됨 (해제)' : '📌 이 날 일정 확정하기'}
        </button>

        {nextDate && (
          <button
            type="button"
            className="btn-day-next"
            disabled={!isActiveConfirmed}
            title={isActiveConfirmed ? undefined : '먼저 이 날 일정을 확정해주세요'}
            onClick={() => onSelectDate(nextDate)}
          >
            다음 날 보기 →
          </button>
        )}
      </div>
    </div>
  );
}
