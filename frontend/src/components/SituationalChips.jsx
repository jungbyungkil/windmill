import InfoTooltip from './InfoTooltip';
import { SITUATIONAL_LABELS } from '../constants';

/**
 * 규칙 추론/수동 보정된 상황 태그. 값이 없으면 칩을 그리지 않는다.
 * 우천/혼잡 민감도는 "그래서 지금 가도 되나?"가 바로 와닿게 짧은 라벨 + 탭하면 여는 툴팁으로
 * 보여준다(2026-09-15 핸드오프 브리프 8: 내부 코드값은 그대로 두고 표시 문구만 교체).
 */
export default function SituationalChips({
  indoor,
  rainSensitivity,
  congestionSensitivity,
  inferredSource,
}) {
  const chips = [];
  if (indoor === true) chips.push({ key: 'indoor', label: '실내' });
  if (indoor === false) chips.push({ key: 'outdoor', label: '실외' });
  const rain = SITUATIONAL_LABELS.RAIN[rainSensitivity];
  if (rain) chips.push({ key: 'rain', ...rain });
  const crowd = SITUATIONAL_LABELS.CROWD[congestionSensitivity];
  if (crowd) chips.push({ key: 'crowd', ...crowd });
  if (chips.length === 0) return null;
  const source = inferredSource === 'MANUAL' ? '직접 수정' : inferredSource ? '자동' : null;
  return (
    <div className="situational-chips">
      {chips.map((c) => (
        c.tooltip ? (
          <InfoTooltip key={c.key} text={c.tooltip}>
            <span className="situational-chip">{c.label}</span>
          </InfoTooltip>
        ) : (
          <span key={c.key} className="situational-chip">{c.label}</span>
        )
      ))}
      {source && <span className="situational-source">{source}</span>}
    </div>
  );
}
