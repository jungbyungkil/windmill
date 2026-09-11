import { sanitizeApiText } from '../utils/sanitizeApiText';

/** 개요(overview). 빈 값은 렌더하지 않는다. */
export default function PlaceOverview({ text, className = '' }) {
  const value = sanitizeApiText(typeof text === 'string' ? text.trim() : '');
  if (!value) return null;
  return <p className={`place-overview ${className}`.trim()}>{value}</p>;
}
