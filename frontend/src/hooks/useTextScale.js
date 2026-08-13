import { useState, useEffect, useCallback } from 'react';

const KEY = 'windtrail:textScale';
const VALID = ['default', 'large', 'xl'];

function readStored() {
  const v = localStorage.getItem(KEY);
  return VALID.includes(v) ? v : 'default';
}

/** 어르신 접근성 - 글씨/버튼 크기 3단계. index.html 인라인 스크립트가 첫 페인트 전에도 미리 적용한다. */
export default function useTextScale() {
  const [scale, setScaleState] = useState(readStored);

  useEffect(() => {
    if (scale === 'default') {
      delete document.documentElement.dataset.textScale;
    } else {
      document.documentElement.dataset.textScale = scale;
    }
  }, [scale]);

  const setScale = useCallback((value) => {
    if (!VALID.includes(value)) return;
    localStorage.setItem(KEY, value);
    setScaleState(value);
  }, []);

  return [scale, setScale];
}
