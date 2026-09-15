import { useState, useCallback } from 'react';
import { readContentId } from '../utils/itineraryMembership';

const BASKET_KEY = 'windtrail:tripBasket';

function readBasket() {
  try {
    const raw = localStorage.getItem(BASKET_KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    if (!parsed || !Array.isArray(parsed.items)) return { regionCode: null, items: [] };
    return parsed;
  } catch {
    return { regionCode: null, items: [] };
  }
}

function writeBasket(basket) {
  try {
    localStorage.setItem(BASKET_KEY, JSON.stringify(basket));
  } catch {
    /* 저장 실패해도 이번 방문 동안은 메모리 상태로 동작한다 */
  }
}

function clearBasketStorage() {
  localStorage.removeItem(BASKET_KEY);
}

/**
 * 여행 바구니(2026-09-15 핸드오프 브리프: 지도 다중 선택 → 확인 팝업 일괄 추가) - 지도·검색에서
 * 담은 장소를 "일정에 반영" 확정 전까지 보관하는 중간 공간. 쇼핑 장바구니와 같은 성질:
 * 지속성(화면 이동해도 유지) · 가역성(개별/전체 빼기) · 확정성(반영 전엔 일정에 영향 없음).
 *
 * localStorage에 지역 코드와 함께 저장(9.7절) - 다른 지역 장소가 한 바구니에 섞이지 않게, 재진입
 * 시 지역이 다르면 상위(App.jsx)가 조용히 폐기한다. 담기는 서버가 요구하는 장소 스냅샷 필드를
 * 그대로 들고 있어(기존 addItem/applyAlternative가 이미 이 스냅샷을 매번 클라이언트에서 받는
 * 구조라 재조회 단계 자체가 없음) 재조회 없이 바로 일괄 추가 API에 넘길 수 있다.
 */
export default function useTripBasket() {
  const [basket, setBasket] = useState(readBasket);

  const isInBasket = useCallback(
    (place) => basket.items.some((i) => readContentId(i) === readContentId(place)),
    [basket],
  );

  const add = useCallback((place, regionCode) => {
    setBasket((prev) => {
      const id = readContentId(place);
      if (!id || prev.items.some((i) => readContentId(i) === id)) return prev;
      const next = {
        regionCode: prev.regionCode ?? regionCode ?? null,
        items: [...prev.items, { ...place, addedAt: new Date().toISOString() }],
      };
      writeBasket(next);
      return next;
    });
  }, []);

  const remove = useCallback((place) => {
    setBasket((prev) => {
      const id = readContentId(place);
      const next = { ...prev, items: prev.items.filter((i) => readContentId(i) !== id) };
      writeBasket(next);
      return next;
    });
  }, []);

  const toggle = useCallback((place, regionCode) => {
    if (isInBasket(place)) remove(place);
    else add(place, regionCode);
  }, [isInBasket, add, remove]);

  const clear = useCallback(() => {
    clearBasketStorage();
    setBasket((prev) => ({ regionCode: prev.regionCode, items: [] }));
  }, []);

  /** 지역 변경 확인 다이얼로그에서 "계속"을 눌렀을 때만 호출 - 바구니를 새 지역으로 완전히 비운다. */
  const resetForRegionChange = useCallback((newRegionCode) => {
    clearBasketStorage();
    setBasket({ regionCode: newRegionCode, items: [] });
  }, []);

  /**
   * 앱 재진입 시 저장된 지역과 현재 여행 지역이 다르면 다이얼로그 없이 조용히 폐기한다(9.7절 마지막
   * 항목). 호출부(App.jsx)가 itinerary.signguFullCode를 안 시점에 직접 부른다.
   */
  const discardIfRegionMismatch = useCallback((currentRegionCode) => {
    if (!currentRegionCode) return;
    setBasket((prev) => {
      if (prev.regionCode === currentRegionCode) return prev;
      // 지역이 다르면(담긴 게 있든 없든) 조용히 현재 지역으로 맞춘다 - 있었다면 폐기.
      const next = { regionCode: currentRegionCode, items: [] };
      writeBasket(next);
      return next;
    });
  }, []);

  return {
    items: basket.items,
    count: basket.items.length,
    regionCode: basket.regionCode,
    isInBasket,
    add,
    remove,
    toggle,
    clear,
    resetForRegionChange,
    discardIfRegionMismatch,
  };
}
