import { useEffect, useId, useRef, useState } from 'react';

// 전역 "지금 열려 있는 툴팁" 신호 - 모든 InfoTooltip 인스턴스가 구독해, 하나가 열리면 나머지는
// 스스로 닫는다(2026-09-15 핸드오프 브리프 8.4: "동시에 하나만 열림"). 컨텍스트/전역 상태 없이
// 최소한으로 구현.
const listeners = new Set();
function announceOpen(id) {
  listeners.forEach((fn) => fn(id));
}

/**
 * 탭하면 여는 말풍선 툴팁(모바일 전제 - hover 아님). 배지 등 children을 감싸 트리거로 만든다.
 * - 재탭 또는 화면 바깥 탭 시 닫힘
 * - 동시에 하나만 열림(다른 인스턴스가 열리면 자동으로 닫힘)
 * - 트리거가 화면 가장자리에 가까우면 말풍선 정렬을 안쪽으로 보정해 잘리지 않게 함
 * - aria-describedby + 키보드 포커스(버튼이라 Tab/Enter/Space 기본 지원)
 */
export default function InfoTooltip({ children, text, className = '' }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [align, setAlign] = useState('center');
  const wrapRef = useRef(null);

  useEffect(() => {
    const onOtherOpen = (openId) => {
      if (openId !== id) setOpen(false);
    };
    listeners.add(onOtherOpen);
    return () => listeners.delete(onOtherOpen);
  }, [id]);

  useEffect(() => {
    if (!open) return undefined;
    function handleOutside(e) {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) {
        setOpen(false);
      }
    }
    document.addEventListener('pointerdown', handleOutside);
    return () => document.removeEventListener('pointerdown', handleOutside);
  }, [open]);

  function toggle() {
    if (open) {
      setOpen(false);
      return;
    }
    if (wrapRef.current) {
      const rect = wrapRef.current.getBoundingClientRect();
      const vw = window.innerWidth;
      if (rect.left < vw * 0.25) setAlign('left');
      else if (rect.right > vw * 0.75) setAlign('right');
      else setAlign('center');
    }
    announceOpen(id);
    setOpen(true);
  }

  return (
    <span className={`info-tooltip-wrap ${className}`} ref={wrapRef}>
      <button
        type="button"
        className="info-tooltip-trigger"
        aria-describedby={open ? id : undefined}
        aria-expanded={open}
        onClick={toggle}
      >
        {children}
      </button>
      {open && (
        <span role="tooltip" id={id} className={`info-tooltip-bubble align-${align}`}>
          {text}
        </span>
      )}
    </span>
  );
}
