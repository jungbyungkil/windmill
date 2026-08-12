import { useEffect, useRef, useState } from 'react';
import useModalHistory from '../hooks/useModalHistory';

const LANGS = [
  { value: 'ko', label: '한국어', speech: 'ko-KR' },
  { value: 'en', label: 'English', speech: 'en-US' },
  { value: 'ja', label: '日本語', speech: 'ja-JP' },
  { value: 'zh', label: '中文', speech: 'zh-CN' },
];

function pickVoice(langCode) {
  if (typeof window === 'undefined' || !window.speechSynthesis) return null;
  const voices = window.speechSynthesis.getVoices();
  if (!voices.length) return null;
  const prefix = (langCode || 'ko-KR').slice(0, 2).toLowerCase();
  return (
    voices.find((v) => v.lang?.toLowerCase().startsWith(prefix) && /female|woman|yuna|sora|google/i.test(v.name))
    || voices.find((v) => v.lang?.toLowerCase().startsWith(prefix))
    || null
  );
}

/**
 * 케이팝 콘셉트 도슨트 - 서버 TTS(audioUrl) 우선, 없으면 브라우저 SpeechSynthesis로 읽어 준다.
 */
export default function DocentModal({
  open,
  placeName,
  script,
  audioUrl,
  loading,
  error,
  language,
  onLanguageChange,
  onClose,
}) {
  const [speaking, setSpeaking] = useState(false);
  const [speechSupported, setSpeechSupported] = useState(false);
  const utteranceRef = useRef(null);

  function stopSpeech() {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    utteranceRef.current = null;
    setSpeaking(false);
  }

  function speakScript() {
    if (!script || typeof window === 'undefined' || !window.speechSynthesis) return;
    stopSpeech();
    const langMeta = LANGS.find((l) => l.value === language) || LANGS[0];
    const utter = new SpeechSynthesisUtterance(script);
    utter.lang = langMeta.speech;
    utter.rate = language === 'ko' ? 1.05 : 1.0;
    utter.pitch = 1.08;
    const voice = pickVoice(langMeta.speech);
    if (voice) utter.voice = voice;
    utter.onend = () => setSpeaking(false);
    utter.onerror = () => setSpeaking(false);
    utteranceRef.current = utter;
    setSpeaking(true);
    window.speechSynthesis.speak(utter);
  }

  useEffect(() => {
    setSpeechSupported(typeof window !== 'undefined' && 'speechSynthesis' in window);
  }, []);

  // 보이스 목록은 비동기로 채워지는 브라우저가 많음
  useEffect(() => {
    if (!speechSupported) return undefined;
    const load = () => pickVoice('ko-KR');
    load();
    window.speechSynthesis.addEventListener('voiceschanged', load);
    return () => window.speechSynthesis.removeEventListener('voiceschanged', load);
  }, [speechSupported]);

  // 모달 닫힘·언어/스크립트 바뀌면 기존 음성 중지
  useEffect(() => {
    if (!open) {
      stopSpeech();
      return undefined;
    }
    return () => stopSpeech();
  }, [open]);

  useEffect(() => {
    stopSpeech();
  }, [language, script, audioUrl]);

  // 서버 오디오가 없을 때 스크립트 준비되면 자동 읽기 시도
  useEffect(() => {
    if (!open || loading || error || audioUrl || !script || !speechSupported) return undefined;
    const t = setTimeout(() => speakScript(), 250);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, loading, error, audioUrl, script, language, speechSupported]);

  function handleClose() {
    stopSpeech();
    onClose?.();
  }

  useModalHistory(open, handleClose);

  if (!open) return null;

  const showBrowserSpeech = !loading && !error && script && !audioUrl && speechSupported;

  return (
    <div className="modal-overlay" onClick={handleClose}>
      <div className="modal-panel docent-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>🎧 바람이 Docent</h3>
          <button className="icon-btn" type="button" onClick={handleClose}>✕</button>
        </div>

        <div className="docent-lang-row" role="group" aria-label="Language">
          {LANGS.map((l) => (
            <button
              key={l.value}
              type="button"
              className={`docent-lang-btn ${language === l.value ? 'selected' : ''}`}
              onClick={() => onLanguageChange?.(l.value)}
              disabled={loading}
            >
              {l.label}
            </button>
          ))}
        </div>

        <p className="modal-desc docent-concept">
          {language === 'ko'
            ? 'K-pop 브이로그 톤으로 안내해요.'
            : 'K-pop style travel buddy — bright, short, and fun for visitors.'}
        </p>

        <div className="docent-card">
          <div className="docent-avatar">🌬️</div>
          <div className="docent-place">{placeName}</div>

          {loading && (
            <div className="skeleton-list">
              <div className="skeleton-line" />
              <div className="skeleton-line" />
              <div className="skeleton-line short" />
            </div>
          )}

          {!loading && error && <p className="error-msg">❌ {error}</p>}

          {!loading && !error && audioUrl && (
            <audio className="docent-audio" controls autoPlay src={audioUrl}>
              브라우저가 오디오 재생을 지원하지 않아요.
            </audio>
          )}

          {showBrowserSpeech && (
            <div className="docent-speech-controls">
              <button
                type="button"
                className="btn-primary docent-speak-btn"
                onClick={() => (speaking ? stopSpeech() : speakScript())}
              >
                {speaking ? '⏹ 중지' : '🔊 소리로 듣기'}
              </button>
              <p className="docent-speech-hint">기기 음성으로 읽어 드려요</p>
            </div>
          )}

          {!loading && !error && script && !audioUrl && !speechSupported && (
            <p className="docent-speech-hint">이 브라우저는 음성 읽기를 지원하지 않아요. 아래 스크립트를 읽어 주세요.</p>
          )}

          {!loading && !error && script && (
            <blockquote className="docent-script">{script}</blockquote>
          )}
        </div>
      </div>
    </div>
  );
}
