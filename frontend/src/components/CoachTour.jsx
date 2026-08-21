import { useEffect, useLayoutEffect, useState } from 'react';

/**
 * 첫 사용자를 위한 다음 포인트 안내.
 * localStorage에 투어 id를 저장해 한 번 본 사람은 다시 띄우지 않는다.
 */
export default function CoachTour({ tourId, steps, enabled = true }) {
  const storageKey = `windtrail:coach:${tourId}`;
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!enabled || !tourId || !steps?.length) return undefined;
    try {
      if (localStorage.getItem(storageKey) === 'done') return undefined;
    } catch {
      /* continue */
    }
    const timer = setTimeout(() => setOpen(true), 400);
    return () => clearTimeout(timer);
  }, [enabled, storageKey, tourId, steps]);

  const step = open ? steps[index] : null;

  useLayoutEffect(() => {
    if (!step?.selector) {
      setRect(null);
      return undefined;
    }
    function measure() {
      const el = document.querySelector(step.selector);
      if (!el) {
        setRect(null);
        return;
      }
      const box = el.getBoundingClientRect();
      setRect({
        top: box.top + window.scrollY - 8,
        left: box.left + window.scrollX - 8,
        width: box.width + 16,
        height: box.height + 16,
      });
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    measure();
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [step]);

  function finish() {
    try {
      localStorage.setItem(storageKey, 'done');
    } catch {
      /* ignore */
    }
    setOpen(false);
  }

  function next() {
    if (index >= steps.length - 1) {
      finish();
      return;
    }
    setIndex((n) => n + 1);
  }

  if (!open || !step) return null;

  const tipStyle = rect
    ? { top: rect.top + rect.height + 12, left: Math.max(16, rect.left) }
    : { top: '30%', left: 16, right: 16 };

  return (
    <div className="coach-overlay" role="dialog" aria-modal="true" aria-labelledby="coach-title">
      {rect && (
        <div
          className="coach-spotlight"
          style={{
            top: rect.top,
            left: rect.left,
            width: rect.width,
            height: rect.height,
          }}
        />
      )}
      <div className="coach-tip" style={tipStyle}>
        <p className="coach-step">{index + 1} / {steps.length}</p>
        <h2 id="coach-title" className="coach-title">{step.title}</h2>
        <p className="coach-body">{step.body}</p>
        <div className="coach-actions">
          <button type="button" className="coach-skip" onClick={finish}>건너뛰기</button>
          <button type="button" className="btn-primary coach-next" onClick={next}>
            {index >= steps.length - 1 ? '시작하기' : '다음'}
          </button>
        </div>
      </div>
    </div>
  );
}
