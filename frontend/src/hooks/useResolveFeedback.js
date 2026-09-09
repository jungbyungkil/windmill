import { useCallback, useEffect, useRef, useState } from 'react';
import { resolveTrigger, ACTION_COPY } from '../utils/triggerResolve';

/**
 * 액션 즉시 피드백 공통 훅 — 동선 최적화 / 혼잡도(비·폭염) 대안이 같은 패턴을 공유한다.
 *
 *  1. 낙관적 UI: 버튼을 누르는 즉시 핀휠을 성공 스킨(optimistic)으로 전환한다.
 *  2. 즉시 트리거 해소: API 성공 시 폴링을 기다리지 않고 resolveTrigger로 방금 해소한
 *     변수를 클라이언트에서 제거 → 다른 변수가 없으면 핀휠이 곧바로 🟢로 바뀐다.
 *  3. 실패 롤백: 원래 트리거 상태로 되돌리고(rollback 애니메이션 0.45초) 실패 토스트를 띄운다.
 *  4. 서버 정합: 성공 후 RECONCILE_MS 뒤 refreshTrigger를 한 번 호출해 서버 판정과 맞춘다.
 *
 * 다른 트리거(영업시간 해결 등)도 avoidHint만 넘기면 그대로 재사용할 수 있다.
 *
 * runResolve : action 하나로 끝나는 단순 플로우용 (성공/실패만 갈림)
 * beginOptimistic / commitResolve / rollbackResolve : 중간 분기(대안 없음 등)가 있는
 *   복잡한 플로우가 직접 단계를 제어할 때 쓰는 하위 프리미티브
 */

export const TOAST_MS = 2000;
export const ROLLBACK_MS = 450;
export const RECONCILE_MS = 12000;

export function useResolveFeedback({ triggerRef, setTrigger, refreshTrigger }) {
  const [toast, setToast] = useState(null); // { id, tone: 'success' | 'error', text }
  const [optimistic, setOptimistic] = useState(null); // { hint, message }
  const [rollback, setRollback] = useState(false);
  const timers = useRef([]);
  const snapshotRef = useRef(null); // 낙관적 진입 시점의 트리거 상태 (롤백 대상)

  const track = useCallback((id) => { timers.current.push(id); return id; }, []);

  useEffect(() => () => { timers.current.forEach(clearTimeout); timers.current = []; }, []);

  const showToast = useCallback((tone, text) => {
    if (!text) return;
    const id = Date.now() + Math.random();
    setToast({ id, tone, text });
    track(setTimeout(() => {
      setToast((cur) => (cur && cur.id === id ? null : cur));
    }, TOAST_MS));
  }, [track]);

  const dismissToast = useCallback(() => setToast(null), []);

  /** 낙관적 성공 스킨 진입 — 이 시점의 트리거를 롤백용으로 저장 */
  const beginOptimistic = useCallback((avoidHint, message) => {
    const copy = ACTION_COPY[avoidHint] || {};
    snapshotRef.current = triggerRef.current;
    setRollback(false);
    setOptimistic({ hint: avoidHint, message: message || copy.optimistic || '적용하고 있어요…' });
  }, [triggerRef]);

  /** 성공 확정 — 트리거 즉시 해소 + 토스트 + 서버 정합 예약 */
  const commitResolve = useCallback((avoidHint, toastText) => {
    const copy = ACTION_COPY[avoidHint] || {};
    setTrigger(resolveTrigger(triggerRef.current, avoidHint));
    setOptimistic(null);
    snapshotRef.current = null;
    showToast('success', toastText || copy.toast || '적용했어요 🍃');
    track(setTimeout(() => { refreshTrigger?.(); }, RECONCILE_MS));
  }, [triggerRef, setTrigger, refreshTrigger, showToast, track]);

  /** 실패 롤백 — 원래 트리거로 되돌리고 롤백 애니메이션 + 실패 토스트 */
  const rollbackResolve = useCallback((avoidHint, toastText) => {
    const copy = ACTION_COPY[avoidHint] || {};
    setOptimistic(null);
    if (snapshotRef.current !== null) {
      setTrigger(snapshotRef.current);
      snapshotRef.current = null;
    }
    setRollback(true);
    track(setTimeout(() => setRollback(false), ROLLBACK_MS));
    showToast('error', toastText || copy.fail || '적용에 실패했어요, 다시 시도해주세요');
  }, [setTrigger, showToast, track]);

  /** 분기 없이 취소(대안 없음 등) — 조용히 낙관적 스킨만 걷어낸다 (토스트/롤백 애니메이션 없음) */
  const cancelOptimistic = useCallback((toastText) => {
    setOptimistic(null);
    if (snapshotRef.current !== null) {
      setTrigger(snapshotRef.current);
      snapshotRef.current = null;
    }
    if (toastText) showToast('error', toastText);
  }, [setTrigger, showToast]);

  /**
   * 단순 플로우용 래퍼.
   * @param {'ROUTE'|'CROWD'|'HEAT'|'WEATHER'|'BUSINESS'} avoidHint
   * @param {() => Promise<any>} action
   * @param {(result:any) => string} [successToast]
   * @param {string} [optimisticMessage]
   * @param {string} [failToast]
   */
  const runResolve = useCallback(async ({
    avoidHint, action, successToast, optimisticMessage, failToast,
  }) => {
    beginOptimistic(avoidHint, optimisticMessage);
    try {
      const result = await action();
      commitResolve(avoidHint, successToast && successToast(result));
      return result;
    } catch (e) {
      rollbackResolve(avoidHint, failToast);
      throw e;
    }
  }, [beginOptimistic, commitResolve, rollbackResolve]);

  return {
    toast,
    optimistic,
    rollback,
    showToast,
    dismissToast,
    runResolve,
    beginOptimistic,
    commitResolve,
    rollbackResolve,
    cancelOptimistic,
  };
}
