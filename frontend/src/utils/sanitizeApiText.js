const BR_PATTERN = /<br\s*\/?>/gi;
const TAG_PATTERN = /<\/?[a-z][^>]*>/gi;

/**
 * TourAPI(KorService2 등) 텍스트 필드(정기휴무·이용시간·개요·부가정보 등)에 원문 그대로 섞여 오는
 * HTML을 정리한다 - React는 문자열을 그대로 이스케이프해 보여줘서 "<br>"가 줄바꿈이 아니라
 * 글자 그대로 화면에 찍히던 문제(2026-09-11 사용자 제보 - 휴무일 안내에 "<br>" 노출).
 * <br>류는 실제 줄바꿈으로 바꾸고, 그 외 태그는 제거한다(구조화 안 된 외부 데이터라 원본을
 * 신뢰하지 않음). 이 함수가 반환한 줄바꿈이 보이려면 렌더링 요소에 white-space: pre-line이
 * 같이 있어야 한다.
 */
export function sanitizeApiText(text) {
  if (typeof text !== 'string' || !text) return text;
  return text
    .replace(BR_PATTERN, '\n')
    .replace(TAG_PATTERN, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
