const RAIN_LABEL = {
  SENSITIVE: '우천 민감',
  INSENSITIVE: '우천 둔감',
};

const CROWD_LABEL = {
  SENSITIVE: '혼잡 민감',
  INSENSITIVE: '혼잡 둔감',
};

/** 규칙 추론/수동 보정된 상황 태그. 값이 없으면 칩을 그리지 않는다. */
export default function SituationalChips({
  indoor,
  rainSensitivity,
  congestionSensitivity,
  inferredSource,
}) {
  const chips = [];
  if (indoor === true) chips.push({ key: 'indoor', label: '실내' });
  if (indoor === false) chips.push({ key: 'outdoor', label: '실외' });
  if (RAIN_LABEL[rainSensitivity]) {
    chips.push({ key: 'rain', label: RAIN_LABEL[rainSensitivity] });
  }
  if (CROWD_LABEL[congestionSensitivity]) {
    chips.push({ key: 'crowd', label: CROWD_LABEL[congestionSensitivity] });
  }
  if (chips.length === 0) return null;
  const source = inferredSource === 'MANUAL' ? '직접 수정' : inferredSource ? '자동' : null;
  return (
    <div className="situational-chips">
      {chips.map((c) => (
        <span key={c.key} className="situational-chip">{c.label}</span>
      ))}
      {source && <span className="situational-source">{source}</span>}
    </div>
  );
}
