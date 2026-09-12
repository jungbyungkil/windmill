const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/**
 * 브라우저 로컬 타임존 설정과 무관하게 KST(UTC+9) 기준 "지금"의 ISO 문자열을 만든다.
 * 서버는 UTC를 쓰므로 프론트에서 로컬 저장 타임스탬프를 만들 때는 이 함수로 KST를 명시한다.
 */
export function nowKstIso() {
  const shifted = new Date(Date.now() + KST_OFFSET_MS);
  return shifted.toISOString().replace(/\.\d{3}Z$/, '+09:00');
}
