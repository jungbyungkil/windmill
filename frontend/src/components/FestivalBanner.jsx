import { openExternalLink } from '../utils/externalLink';

function formatDate(yyyyMMdd) {
  if (!yyyyMMdd || yyyyMMdd.length !== 8) return yyyyMMdd;
  return `${yyyyMMdd.slice(4, 6)}.${yyyyMMdd.slice(6, 8)}`;
}

/** 본문 흐름과 분리된 하단 참고 영역 - 축제 정보는 선택 사항 */
export default function FestivalBanner({ festivals, onAdd, addingId }) {
  if (!festivals || festivals.length === 0) return null;

  return (
    <aside className="festival-banner festival-ref" aria-label="참고: 지역 축제">
      <div className="festival-banner-head">
        <span className="festival-ref-badge">참고</span>
        <div>
          <div className="festival-banner-title">이 기간 · 이 지역 축제</div>
          <p className="festival-banner-sub">일정 필수는 아니에요. 관심 있으면 담아 보세요. 이름을 누르면 축제 페이지로 이동해요.</p>
        </div>
      </div>
      <div className="festival-card-list">
        {festivals.map((f) => (
          <div key={f.contentId} className="festival-card">
            {f.thumbnailUrl && (
              <button
                type="button"
                className="festival-thumb-btn"
                onClick={() => openExternalLink(f.homepageUrl)}
                disabled={!f.homepageUrl}
                aria-label={f.homepageUrl ? `${f.placeName} 축제 페이지 열기` : undefined}
              >
                <img className="festival-thumb" src={f.thumbnailUrl} alt="" />
              </button>
            )}
            <div className="festival-info">
              {f.homepageUrl ? (
                <button
                  type="button"
                  className="festival-name festival-name-link"
                  onClick={() => openExternalLink(f.homepageUrl)}
                >
                  {f.placeName}
                  <span className="festival-ext" aria-hidden="true">↗</span>
                </button>
              ) : (
                <div className="festival-name">{f.placeName}</div>
              )}
              <div className="festival-period">{formatDate(f.eventStartDate)} ~ {formatDate(f.eventEndDate)}</div>
              {f.addr1 && <div className="festival-addr">{f.addr1}</div>}
            </div>
            <button
              type="button"
              className="btn-pinwheel-cta secondary festival-add-btn"
              onClick={() => onAdd?.(f)}
              disabled={addingId === f.contentId}
            >
              {addingId === f.contentId ? '추가 중...' : '담기'}
            </button>
          </div>
        ))}
      </div>
    </aside>
  );
}
