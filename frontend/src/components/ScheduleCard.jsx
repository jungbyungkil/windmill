const CATEGORY_COLORS = {
  관광: '#4CAF50',
  식사: '#FF9800',
  숙박: '#2196F3',
  쇼핑: '#9C27B0',
  default: '#607D8B',
};

export default function ScheduleCard({ date, items }) {
  return (
    <div className="schedule-card">
      <h3 className="schedule-date">{date}</h3>
      <div className="schedule-items">
        {items.map((item, idx) => (
          <div key={idx} className="schedule-item">
            <div className="schedule-time">{item.time}</div>
            <div
              className="schedule-dot"
              style={{ background: CATEGORY_COLORS[item.category] || CATEGORY_COLORS.default }}
            />
            <div className="schedule-content">
              <div className="schedule-place">{item.place}</div>
              <div className="schedule-activity">{item.activity}</div>
              {item.address && <div className="schedule-address">📍 {item.address}</div>}
              {item.note && <div className="schedule-note">💡 {item.note}</div>}
            </div>
            <span
              className="schedule-category"
              style={{ background: CATEGORY_COLORS[item.category] || CATEGORY_COLORS.default }}
            >
              {item.category}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
