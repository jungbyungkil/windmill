import { sanitizeApiText } from './sanitizeApiText';

const HARD_CAP = 10;

/**
 * 개요(overview) 텍스트에서 카드 이름 아래 붙일 짧은 한 줄 소개를 뽑아낸다.
 * 첫 줄의 첫 문장(마침표·느낌표·물음표 기준)만 취하고, 문장부호가 없으면 그 줄 전체를 쓴다.
 * 최대 10자로 캡을 걸어 카드 폭에서 글자가 잘리지 않게 한다(2026-09-12 사용자 제보 -
 * 소개글이 이름과 한 줄에서 겹쳐 중간에 잘려 보이던 문제).
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
