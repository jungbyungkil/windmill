function toLocalYmd(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function enumerateDates(startDate, endDate) {
  const dates = [];
  const cursor = new Date(startDate + 'T00:00:00');
  const last = new Date(endDate + 'T00:00:00');
  while (cursor <= last) {
    dates.push(toLocalYmd(cursor));
    cursor.setDate(cursor.getDate() + 1);
  }
  return dates;
}

function formatDay(dateStr, dayIndex) {
  const d = new Date(dateStr + 'T00:00:00');
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][d.getDay()];
  const md = `${d.getMonth() + 1}/${d.getDate()}`;
  return { dayNo: dayIndex + 1, md, weekday };
}

/**
 * 다일 여행 일차 탭 — 개수 뱃지, 가로 스크롤, 확정/다음 날 액션.
 */
export default function DayTabs({
  startDate,
  endDate,
  activeDate,
  confirmedDates,
  itemCounts = {},
  onSelectDate,
  onToggleConfirm,
  confirming,
  onPlanDay,
}) {
  const dates = enumerateDates(startDate, endDate);
  if (dates.length <= 1) return null;

  const activeIndex = dates.indexOf(activeDate);
  const isActiveConfirmed = confirmedDates?.includes(activeDate);
  const nextDate = activeIndex >= 0 && activeIndex < dates.length - 1 ? dates[activeIndex + 1] : null;
  const prevDate = activeIndex > 0 ? dates[activeIndex - 1] : null;
  const activeCount = itemCounts[activeDate] || 0;
  const activeMeta = activeDate ? formatDay(activeDate, Math.max(activeIndex, 0)) : null;

  return (
    <div className="day-tabs">
      <div className="day-tabs-summary">
        <strong>{dates.length}일 여행</strong>
        {activeMeta && (
          <span>
            {activeMeta.dayNo}일차 · {activeMeta.md} ({activeMeta.weekday}) · {activeCount}곳
          </span>
        )}
      </div>

      <div className="day-tabs-row" role="tablist" aria-label="여행 일차">
        {dates.map((date, i) => {
          const meta = formatDay(date, i);
          const count = itemCounts[date] || 0;
          const confirmed = confirmedDates?.includes(date);
          return (
            <button
              key={date}
              type="button"
              role="tab"
              aria-selected={date === activeDate}
              className={`day-tab ${date === activeDate ? 'active' : ''} ${confirmed ? 'confirmed' : ''} ${count === 0 ? 'empty' : ''}`}
              onClick={() => onSelectDate(date)}
            >
              <span className="day-tab-no">{meta.dayNo}일</span>
              <span className="day-tab-md">{meta.md}</span>
              <span className={`day-tab-count ${count === 0 ? 'zero' : ''}`}>
                {confirmed ? '✓' : ''}{count}
              </span>
            </button>
          );
        })}
      </div>

      <div className="day-tabs-actions">
        {prevDate && (
          <button type="button" className="btn-day-nav" onClick={() => onSelectDate(prevDate)}>
            ← 이전
          </button>
        )}
        <button
          type="button"
          className={`btn-day-confirm ${isActiveConfirmed ? 'confirmed' : ''}`}
          onClick={() => onToggleConfirm(activeDate, !isActiveConfirmed)}
          disabled={confirming}
        >
          {isActiveConfirmed ? '확정됨' : '이 날 확정'}
        </button>
        {onPlanDay && activeCount === 0 && (
          <button type="button" className="btn-day-plan" onClick={() => onPlanDay(activeDate)}>
            스마트 일정
          </button>
        )}
        {nextDate && (
          <button
            type="button"
            className="btn-day-nav primary"
            onClick={() => onSelectDate(nextDate)}
          >
            다음 →
          </button>
        )}
      </div>
    </div>
  );
}
