import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import useModalHistory from '../hooks/useModalHistory';

const HOUR_LABELS = [12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];

/**
 * 방문 시각 선택 — 30분 단위 아날로그 시계 다이얼.
 * 드래그/탭으로 시·분을 고르고, 확인을 눌러야 반영한다.
 * 바깥을 누르거나 뒤로가기면 취소(네이티브 time 입력의 오전→오후만 바꿔도
 * change가 나가던 문제를 피하기 위해 확정 전까지 onChange를 호출하지 않는다).
 */
export function normalizeTime(raw) {
  if (!raw || typeof raw !== 'string') return '';
  const value = raw.trim();
  if (!value) return '';
  const m = value.match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
  if (!m) return '';
  const hh = Number(m[1]);
  const mm = Number(m[2]);
  if (!Number.isFinite(hh) || !Number.isFinite(mm) || hh < 0 || hh > 23 || mm < 0 || mm > 59) {
    return '';
  }
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

function snapToHalfHour(raw) {
  const value = normalizeTime(raw);
  if (!value) return '';
  const [hh, mm] = value.split(':').map(Number);
  if (mm < 15) return formatHm(hh, 0);
  if (mm < 45) return formatHm(hh, 30);
  return formatHm((hh + 1) % 24, 0);
}

function defaultHalfHour(now = new Date()) {
  return snapToHalfHour(
    `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`,
  );
}

function formatHm(hour, minute) {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function parseParts(value) {
  const snapped = snapToHalfHour(value) || defaultHalfHour();
  const [hour, minute] = snapped.split(':').map(Number);
  return {
    period: hour < 12 ? 'am' : 'pm',
    clockHour: hour % 12,
    minute: minute < 30 ? 0 : 30,
  };
}

function joinParts({ period, clockHour, minute }) {
  const hour = period === 'am'
    ? (clockHour === 0 ? 0 : clockHour)
    : (clockHour === 0 ? 12 : clockHour + 12);
  return formatHm(hour, minute);
}

function angleFromPointer(event, el) {
  const rect = el.getBoundingClientRect();
  const dx = event.clientX - (rect.left + rect.width / 2);
  const dy = event.clientY - (rect.top + rect.height / 2);
  if (dx * dx + dy * dy < 18 * 18) return null;
  return (Math.atan2(dx, -dy) * (180 / Math.PI) + 360) % 360;
}

function clockPointStyle(index, radiusPercent) {
  const rad = ((index * 30) - 90) * (Math.PI / 180);
  return {
    left: `${50 + Math.cos(rad) * radiusPercent}%`,
    top: `${50 + Math.sin(rad) * radiusPercent}%`,
  };
}

function ClockDial({ mode, clockHour, minute, onHourChange, onMinuteChange, onHourCommitted }) {
  const faceRef = useRef(null);
  const draggingRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const modeRef = useRef(mode);
  const onHourChangeRef = useRef(onHourChange);
  const onMinuteChangeRef = useRef(onMinuteChange);
  const onHourCommittedRef = useRef(onHourCommitted);
  modeRef.current = mode;
  onHourChangeRef.current = onHourChange;
  onMinuteChangeRef.current = onMinuteChange;
  onHourCommittedRef.current = onHourCommitted;

  function applyAngle(deg) {
    if (modeRef.current === 'hour') {
      onHourChangeRef.current(Math.round(deg / 30) % 12);
      return;
    }
    const toZero = Math.min(deg, 360 - deg);
    const toThirty = Math.abs(deg - 180);
    onMinuteChangeRef.current(toZero <= toThirty ? 0 : 30);
  }

  function onPointerDown(event) {
    if (event.button != null && event.button !== 0) return;
    const face = faceRef.current;
    if (!face) return;
    event.preventDefault();
    face.setPointerCapture(event.pointerId);
    draggingRef.current = true;
    setDragging(true);
    const deg = angleFromPointer(event, face);
    if (deg != null) applyAngle(deg);
  }

  function onPointerMove(event) {
    if (!draggingRef.current) return;
    const face = faceRef.current;
    if (!face) return;
    const deg = angleFromPointer(event, face);
    if (deg != null) applyAngle(deg);
  }

  function onPointerUp(event) {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    setDragging(false);
    const face = faceRef.current;
    if (face?.hasPointerCapture(event.pointerId)) {
      face.releasePointerCapture(event.pointerId);
    }
    if (modeRef.current === 'hour') {
      onHourCommittedRef.current();
    }
  }

  const handDeg = mode === 'hour' ? clockHour * 30 : (minute === 30 ? 180 : 0);

  return (
    <div className="clock-dial">
      <div
        ref={faceRef}
        className={`clock-face clock-face-${mode}${dragging ? ' is-dragging' : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        role="slider"
        aria-label={mode === 'hour' ? '시 선택' : '분 선택'}
        aria-valuemin={mode === 'hour' ? 1 : 0}
        aria-valuemax={mode === 'hour' ? 12 : 30}
        aria-valuenow={mode === 'hour' ? (clockHour === 0 ? 12 : clockHour) : minute}
        aria-valuetext={mode === 'hour' ? `${clockHour === 0 ? 12 : clockHour}시` : `${minute}분`}
      >
        <svg className="clock-ticks" viewBox="0 0 100 100" aria-hidden="true">
          {HOUR_LABELS.map((_, index) => (
            <line
              key={index}
              x1="50"
              y1="3.2"
              x2="50"
              y2={index % 3 === 0 ? 8.4 : 6.6}
              transform={`rotate(${index * 30} 50 50)`}
            />
          ))}
        </svg>
        <div className="clock-hand" style={{ '--hand-deg': `${handDeg}deg` }} aria-hidden="true">
          <span className="clock-hand-line" />
          <span className="clock-hand-knob" />
        </div>
        <span className="clock-center-dot" aria-hidden="true" />
        {mode === 'hour'
          ? HOUR_LABELS.map((label, index) => (
            <span
              key={label}
              className={`clock-num${clockHour === index ? ' is-selected' : ''}`}
              style={clockPointStyle(index, 36)}
            >
              {label}
            </span>
          ))
          : (
            <>
              <span
                className={`clock-num clock-num-minute${minute === 0 ? ' is-selected' : ''}`}
                style={clockPointStyle(0, 36)}
              >
                00
              </span>
              <span
                className={`clock-num clock-num-minute${minute === 30 ? ' is-selected' : ''}`}
                style={clockPointStyle(6, 36)}
              >
                30
              </span>
            </>
          )}
      </div>
    </div>
  );
}

export default function VisitTimePicker({
  value,
  onChange,
  className = '',
  disabled = false,
  placeholder = '--:--',
  allowEmpty = false,
  title,
  'aria-label': ariaLabel = '방문 시각',
}) {
  const normalized = normalizeTime(value);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState(() => snapToHalfHour(normalized) || defaultHalfHour());
  const [mode, setMode] = useState('hour');
  const triggerRef = useRef(null);
  const dialogRef = useRef(null);
  const committedRef = useRef(normalized);

  useEffect(() => {
    committedRef.current = normalized;
    if (!open) {
      setDraft(snapToHalfHour(normalized) || defaultHalfHour());
    }
  }, [normalized, open]);

  function close() {
    setOpen(false);
    setMode('hour');
  }

  useModalHistory(open, close);

  useEffect(() => {
    if (!open) return undefined;
    const trigger = triggerRef.current;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const id = window.requestAnimationFrame(() => dialogRef.current?.focus());
    function onKey(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => {
      document.body.style.overflow = prevOverflow;
      window.cancelAnimationFrame(id);
      window.removeEventListener('keydown', onKey);
      trigger?.focus();
    };
  }, [open]);

  function openPicker() {
    if (disabled) return;
    setDraft(snapToHalfHour(normalized) || defaultHalfHour());
    setMode('hour');
    setOpen(true);
  }

  function commit(next) {
    const snapped = next ? snapToHalfHour(next) : '';
    if (snapped === committedRef.current) {
      close();
      return;
    }
    committedRef.current = snapped;
    close();
    onChange?.(snapped);
  }

  const parts = parseParts(draft);
  const displayHour = parts.clockHour === 0 ? 12 : parts.clockHour;
  const displayMinute = String(parts.minute).padStart(2, '0');

  const dialog = open ? createPortal(
    <div className="clock-picker-backdrop" role="presentation" onClick={close}>
      <div
        ref={dialogRef}
        className="clock-picker-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="clock-picker-title"
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
      >
        <p id="clock-picker-title" className="clock-picker-title">방문 시각</p>
        <div className="clock-picker-digital" aria-live="polite">
          <span className="clock-picker-period-label">{parts.period === 'am' ? '오전' : '오후'}</span>
          <button
            type="button"
            className={`clock-picker-unit${mode === 'hour' ? ' is-active' : ''}`}
            onClick={() => setMode('hour')}
            aria-pressed={mode === 'hour'}
          >
            {displayHour}
          </button>
          <span className="clock-picker-colon" aria-hidden="true">:</span>
          <button
            type="button"
            className={`clock-picker-unit${mode === 'minute' ? ' is-active' : ''}`}
            onClick={() => setMode('minute')}
            aria-pressed={mode === 'minute'}
          >
            {displayMinute}
          </button>
        </div>
        <div className="clock-picker-ampm" role="group" aria-label="오전 오후">
          <button
            type="button"
            className={parts.period === 'am' ? 'is-active' : ''}
            aria-pressed={parts.period === 'am'}
            onClick={() => setDraft(joinParts({ ...parts, period: 'am' }))}
          >
            오전
          </button>
          <button
            type="button"
            className={parts.period === 'pm' ? 'is-active' : ''}
            aria-pressed={parts.period === 'pm'}
            onClick={() => setDraft(joinParts({ ...parts, period: 'pm' }))}
          >
            오후
          </button>
        </div>
        <ClockDial
          mode={mode}
          clockHour={parts.clockHour}
          minute={parts.minute}
          onHourChange={(clockHour) => setDraft(joinParts({ ...parts, clockHour }))}
          onMinuteChange={(minute) => setDraft(joinParts({ ...parts, minute }))}
          onHourCommitted={() => setMode('minute')}
        />
        <p className="clock-picker-hint">
          {mode === 'hour' ? '시계를 돌리거나 눌러 시를 고르세요' : '00분 또는 30분만 선택할 수 있어요'}
        </p>
        <div className="clock-picker-actions">
          {allowEmpty && (
            <button
              type="button"
              className="clock-picker-empty"
              onClick={() => commit('')}
            >
              자동으로 맞출게요
            </button>
          )}
          <button
            type="button"
            className="clock-picker-confirm"
            onClick={() => commit(draft)}
          >
            확인
          </button>
        </div>
      </div>
    </div>,
    document.body,
  ) : null;

  return (
    <div className={`visit-time-wrap ${className}`.trim()}>
      <button
        ref={triggerRef}
        type="button"
        className={`item-time item-time-picker${normalized ? '' : ' is-empty'}`}
        disabled={disabled}
        title={title}
        aria-label={ariaLabel}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={openPicker}
      >
        {normalized || placeholder}
      </button>
      {dialog}
    </div>
  );
}
