import { useEffect, useRef, useState } from 'react';

/**
 * 방문 시각 선택 — 직접 타이핑 대신 OS 네이티브 타임피커.
 * 유효하지 않은 시각(99 등)을 막을 수 있는 가장 깔끔한 방식.
 *
 * 확정은 blur(피커를 닫은 뒤)에만 한다. Windows 네이티브 피커는 오전→오후만 바꿔도
 * 10:10이 22:10으로 바뀌며 change가 바로 나와, 아직 오후 2시를 고르는 중인데
 * 서버가 마감/겹침으로 거절하고 맨 뒤 오전 일정 앞 빈칸을 제안하는 문제가 있었다.
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
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(normalized);
  const committedRef = useRef(normalized);

  useEffect(() => {
    committedRef.current = normalized;
    if (!open) {
      setDraft(normalized);
    }
  }, [normalized, open]);

  function commit(raw) {
    const next = normalizeTime(raw);
    if (!next || next === committedRef.current) return;
    committedRef.current = next;
    onChange?.(next);
  }

  return (
    <label className={`visit-time-wrap ${className}`.trim()}>
      <span className="sr-only">{ariaLabel}</span>
      <input
        type="time"
        className="item-time item-time-picker"
        value={open ? draft : normalized}
        step={900}
        disabled={disabled}
        onFocus={() => {
          setOpen(true);
          setDraft(normalized);
        }}
        onChange={(e) => {
          const next = normalizeTime(e.target.value);
          if (next) setDraft(next);
        }}
        onBlur={(e) => {
          const next = normalizeTime(e.target.value) || draft;
          setDraft(next);
          setOpen(false);
          commit(next);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur();
        }}
        aria-label={ariaLabel}
      />
    </label>
  );
}
