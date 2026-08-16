import { useEffect, useRef } from 'react';

/**
 * open=true인 동안 history entry를 하나 쌓아, 이 화면 위에서 브라우저/OS 뒤로가기를 누르면
 * 앱 전체가 종료되는 대신 이 모달만 닫히도록 한다(popstate 발생 시 onClose 호출).
 * ✕ 버튼 등 UI로 닫힐 때도(open이 false로 바뀔 때) 쌓아둔 entry를 history.back()으로 되돌려
 * 히스토리 스택이 실제 화면 상태와 어긋나지 않게 맞춘다.
 *
 * ⚠ 모달을 닫으면서 동시에 다른 라우트로 이동하는 경우(예: GNB 메뉴 항목 클릭 - setMenuOpen(false) +
 * navigate('/my-trips')를 한 틱에 같이 호출, 또는 "여행 마무리" 제출 후 itinerary=null → 라우트
 * 가드가 자동으로 "/"로 리다이렉트)엔 history.back()을 부르면 방금 이동한 새 페이지가 취소되고
 * 모달 열기 전 페이지로 되돌아가버린다(2026-08-16 사용자 제보 - "내 여행 관리" 클릭해도 새 화면이
 * 안 뜨던 문제). 모달이 열렸을 때의 경로와 닫히는 시점의 경로가 다르면(=그 사이 다른 곳으로 이미
 * 이동함) history.back()을 생략한다 - 순수하게 배경 클릭·✕ 버튼으로만 닫힐 때만 되돌린다.
 */
export default function useModalHistory(open, onClose) {
  const pushedRef = useRef(false);
  const onCloseRef = useRef(onClose);
  const pathAtOpenRef = useRef(null);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    pathAtOpenRef.current = window.location.pathname;
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
        if (window.location.pathname === pathAtOpenRef.current) {
          window.history.back();
        }
      }
    };
  }, [open]);
}
