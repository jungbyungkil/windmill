/**
 * 방문 시각 선택 — 직접 타이핑 대신 OS 네이티브 타임피커.
 * 유효하지 않은 시각(99 등)을 막을 수 있는 가장 깔끔한 방식.
 */
export function normalizeTime(raw) {
  if (!raw || typeof raw !== 'string') return '';
  const value = raw.trim();
  if (!value) return '';
  // type=time 값은 이미 HH:mm 또는 HH:mm:ss
  const m = value.match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!m) return '';
  const hh = Number(m[1]);
  const mm = Number(m[2]);
  if (!Number.isFinite(hh) || !Number.isFinite(mm) || hh < 0 || hh > 23 || mm < 0 || mm > 59) {
    return '';
  }
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

export default function VisitTimePicker({
  value,
  onChange,
  className = '',
  disabled = false,
  'aria-label': ariaLabel = '방문 시각',
}) {
  const normalized = normalizeTime(value);

  return (
    <label className={`visit-time-wrap ${className}`.trim()}>
      <span className="sr-only">{ariaLabel}</span>
      <input
        type="time"
        className="item-time item-time-picker"
        value={normalized}
        step={900}
        disabled={disabled}
        onChange={(e) => {
          const next = normalizeTime(e.target.value);
          onChange?.(next);
        }}
        aria-label={ariaLabel}
      />
    </label>
  );
}
