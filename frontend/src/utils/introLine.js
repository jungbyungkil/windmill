import { sanitizeApiText } from './sanitizeApiText';

const HARD_CAP = 120;

/**
 * 개요(overview) 텍스트에서 카드 제목 옆에 붙일 짧은 소개 한 줄을 뽑아낸다.
 * 첫 줄의 첫 문장(마침표·느낌표·물음표 기준)만 취하고, 문장부호가 없으면 그 줄 전체를 쓴다.
 * 실제 화면 표시는 CSS 말줄임(ellipsis)이 처리하므로, 여기선 과도하게 긴 원문이 DOM에
 * 그대로 쌓이지 않도록 하드 캡만 둔다.
 */
export function introLine(overview) {
  const cleaned = sanitizeApiText(typeof overview === 'string' ? overview : '');
  if (!cleaned) return '';
  const firstLine = cleaned.split('\n')[0].trim();
  if (!firstLine) return '';
  const match = firstLine.match(/^(.{2,}?[.!?])(?:\s|$)/);
  const sentence = match ? match[1] : firstLine;
  return sentence.length > HARD_CAP ? `${sentence.slice(0, HARD_CAP).trim()}…` : sentence;
}
