/**
 * 여행 중 3대 변수(비·폭염 / 혼잡 / 동선)를 항상 보여 주고, 감지되면 반짝이며 바로 대응하게 한다.
 */
const CARDS = [
  {
    key: 'weather',
    icon: '🌧️',
    title: '비 · 폭염',
    idle: '야외 일정은 실내로 바꿀 수 있어요',
    activeRain: '비가 와요. 실내 일정으로 바꿔 보세요',
    activeHeat: '폭염이에요. 실내 일정으로 바꿔 보세요',
  },
  {
    key: 'crowd',
    icon: '👥',
    title: '혼잡',
    idle: '붐비는 곳은 한산한 곳으로 바꿀 수 있어요',
    active: '혼잡이 감지됐어요. 한산한 곳으로 바꿔 보세요',
  },
  {
    key: 'route',
    icon: '🔀',
    title: '동선',
    idle: '방문 순서를 다시 잡아 이동을 줄일 수 있어요',
    active: '동선이 꼬였어요. 짧은 길로 다시 짜 보세요',
  },
];

export default function VariableActionCards({
  trigger,
  onWeather,
  onCrowd,
  onRoute,
  weatherLoading,
  crowdLoading,
  routeLoading,
}) {
  const rain = Boolean(trigger?.weatherTrigger);
  const heat = Boolean(trigger?.heatTrigger);
  const crowd = Boolean(trigger?.crowdTrigger);
  const route = Boolean(trigger?.routeTangleTrigger || trigger?.travelTimeTrigger);

  const states = {
    weather: {
      active: rain || heat,
      body: heat ? CARDS[0].activeHeat : rain ? CARDS[0].activeRain : CARDS[0].idle,
      loading: weatherLoading,
      onClick: onWeather,
      cta: heat || rain ? '실내로 바꾸기' : '실내 대안 보기',
    },
    crowd: {
      active: crowd,
      body: crowd ? CARDS[1].active : CARDS[1].idle,
      loading: crowdLoading,
      onClick: onCrowd,
      cta: crowd ? '한산한 곳으로' : '한산한 곳 보기',
    },
    route: {
      active: route,
      body: route ? CARDS[2].active : CARDS[2].idle,
      loading: routeLoading,
      onClick: onRoute,
      cta: route ? '동선 다시 짜기' : '동선 최적화',
    },
  };

  return (
    <section className="variable-cards" aria-label="여행 중 변수 대응">
      <div className="variable-cards-head">
        <strong>지금 변수에 대응하기</strong>
        <p>비·폭염, 혼잡, 동선 — 바람따라가 미리 알려주고 바로 바꿔 드려요.</p>
      </div>
      <div className="variable-cards-grid">
        {CARDS.map((card) => {
          const state = states[card.key];
          return (
            <button
              key={card.key}
              type="button"
              className={`variable-card ${state.active ? 'active sparkle' : ''}`}
              onClick={state.onClick}
              disabled={state.loading}
            >
              <span className="variable-card-icon" aria-hidden="true">{card.icon}</span>
              <span className="variable-card-title">{card.title}</span>
              <span className="variable-card-body">{state.body}</span>
              <span className="variable-card-cta">
                {state.loading ? '준비 중...' : state.cta}
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}
