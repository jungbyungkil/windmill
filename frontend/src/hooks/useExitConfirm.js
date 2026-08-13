import { useEffect, useRef, useState } from 'react';

/**
 * 최상위(홈) 화면에서 뒤로가기 시 바로 앱이 닫히지 않고 커스텀 확인 모달을 띄운다.
 * popstate 가로채기: 진입 시 더미 히스토리를 하나 쌓아두고, 뒤로가기로 그게 소비되면
 * 모달을 띄우면서 동시에 더미를 다시 쌓아 "취소"를 누르면 그대로 머무르게 한다.
 * "종료"를 누르면 더 이상 막지 않고 history.back()으로 실제 뒤로가기를 진행시킨다.
 */
export default function useExitConfirm(enabled) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const guardedRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;
    window.history.pushState({ windmillExitGuard: true }, '');
    guardedRef.current = true;

    function handlePopState() {
      if (!guardedRef.current) return;
      setConfirmOpen(true);
      window.history.pushState({ windmillExitGuard: true }, '');
    }
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
      guardedRef.current = false;
      setConfirmOpen(false);
    };
  }, [enabled]);

  function cancel() {
    setConfirmOpen(false);
  }

  function confirmExit() {
    guardedRef.current = false;
    setConfirmOpen(false);
    window.history.back();
  }

  return { confirmOpen, cancel, confirmExit };
}
