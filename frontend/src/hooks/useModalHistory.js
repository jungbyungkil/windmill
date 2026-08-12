import { useEffect, useRef } from 'react';

/**
 * open=true인 동안 history entry를 하나 쌓아, 이 화면 위에서 브라우저/OS 뒤로가기를 누르면
 * 앱 전체가 종료되는 대신 이 모달만 닫히도록 한다(popstate 발생 시 onClose 호출).
 * ✕ 버튼 등 UI로 닫힐 때도(open이 false로 바뀔 때) 쌓아둔 entry를 history.back()으로 되돌려
 * 히스토리 스택이 실제 화면 상태와 어긋나지 않게 맞춘다.
 */
export default function useModalHistory(open, onClose) {
  const pushedRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    window.history.pushState({ windmillModal: true }, '');
    pushedRef.current = true;

    function handlePopState() {
      pushedRef.current = false;
      onCloseRef.current();
    }
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('popstate', handlePopState);
      if (pushedRef.current) {
        pushedRef.current = false;
        window.history.back();
      }
    };
  }, [open]);
}
