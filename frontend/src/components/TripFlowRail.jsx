const STEPS = [
  { key: 'home', n: '1', label: '오늘 일정', hint: '변수 보고 다듬기' },
  { key: 'map', n: '2', label: '지도', hint: '동선 한눈에' },
  { key: 'search', n: '3', label: '검색', hint: '장소 더 담기' },
  { key: 'alerts', n: '4', label: '알림', hint: '실시간 변수' },
  { key: 'profile', n: '5', label: '프로필', hint: '글씨·알림 설정' },
];

/**
 * 여행 화면 한 장에서 홈→지도→검색→알림→프로필 순서를 보여 주고, 누르면 그 구간으로 이동한다.
 */
export default function TripFlowRail({ active, onSelect }) {
  return (
    <nav className="trip-flow-rail" aria-label="오늘 여행 흐름">
      {STEPS.map((step, i) => {
        const isActive = active === step.key;
        return (
          <div key={step.key} className="trip-flow-item">
            {i > 0 && <span className="trip-flow-connector" aria-hidden="true" />}
            <button
              type="button"
              className={`trip-flow-step ${isActive ? 'active' : ''}`}
              onClick={() => onSelect(step.key)}
              aria-current={isActive ? 'step' : undefined}
            >
              <span className="trip-flow-n">{step.n}</span>
              <span className="trip-flow-label">{step.label}</span>
              <span className="trip-flow-hint">{step.hint}</span>
            </button>
          </div>
        );
      })}
    </nav>
  );
}
