function visibleFacts(facts) {
  if (!Array.isArray(facts)) return [];
  return facts.filter((f) => {
    const label = String(f?.label || '').trim();
    const value = String(f?.value || '').trim();
    return label && value;
  });
}

/** detailIntro2 부가 필드. 백엔드가 빈 값을 이미 걸러 주지만 프론트에서도 한 번 더 숨긴다. */
export default function PlaceDetailFacts({ facts, className = '' }) {
  const rows = visibleFacts(facts);
  if (rows.length === 0) return null;
  return (
    <div className={`place-facts ${className}`.trim()}>
      {rows.map((f) => (
        <div key={f.key || f.label} className="reco-info-row place-fact-row">
          <span className="place-fact-label">{f.label}</span>
          <span className="place-fact-value">{f.value}</span>
        </div>
      ))}
    </div>
  );
}
