/**
 * 여행 꿀팁 박스 — 날씨·혼잡·동선 트리거를 딱딱한 배너 대신 팁 카드로 전달.
 */
export default function TravelTipsCard({ tips, level = 'WARNING', title = '여행 꿀팁!' }) {
  if (!tips?.length) return null;
  return (
    <section
      className={`travel-tips-card level-${String(level).toLowerCase()}`}
      aria-label={title}
    >
      <header className="travel-tips-head">
        <span className="travel-tips-badge">✦ {title}</span>
      </header>
      <ul className="travel-tips-list">
        {tips.map((tip) => (
          <li key={tip.id || tip.text}>
            <span className="travel-tips-icon" aria-hidden="true">{tip.icon || '★'}</span>
            <span>{tip.text}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
