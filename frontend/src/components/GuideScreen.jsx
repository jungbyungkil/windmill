const SECTIONS = [
  {
    icon: '🌬️',
    title: '1. 여행 시작하기',
    body: '지역·여행 날짜(당일치기)·동반유형을 고르고 "당일치기 시작하기"를 누르면 준비가 시작돼요. 반려동물·유모차·무장애 이동 옵션은 해당하는 곳만 우선 추천해드려요.',
  },
  {
    icon: '🧭',
    title: '2. 스마트 일정',
    body: '혼잡이 덜하고 동선이 짧은 순서로 자동 구성된 코스를 먼저 보여드려요. 마음에 들면 그대로 담고, 더 둘러보고 싶으면 카테고리 추천이나 AI 일정 짜기로 넘어갈 수 있어요.',
  },
  {
    icon: '🍽️',
    title: '3. 카테고리 추천 · AI 일정 짜기',
    body: '식당·카페·박물관 등 원하는 종류만 골라 더 담거나, 태그·자연어로 원하는 분위기를 알려주면 AI가 후보를 추려드려요. 실제로 검증된 장소 정보만 사용해요.',
  },
  {
    icon: '🌀',
    title: '4. 실시간 변수 대응(바람개비)',
    body: '일정 화면 위쪽 바람개비가 지금 날씨·혼잡·영업 상황을 알려줘요. 비/폭염/혼잡이 감지되면 대안 코스를 추천받거나, 영향받은 장소만 자동으로 바꿀 수 있어요.',
  },
  {
    icon: '🔗',
    title: '5. 일정 공유 · 여행 마무리',
    body: '"일정 공유"로 링크를 만들어 함께 가는 사람에게 보내보세요. 여행을 다녀온 뒤 "여행 마무리"에서 별점과 후기를 남기면 같은 지역을 찾는 다른 여행자에게 추천으로 쓰여요.',
  },
];

/** 전체 메뉴 > 이용 가이드 - 전체 흐름 요약. 화면별 개별 도움말(?)은 이번 스코프에서 제외했어요. */
export default function GuideScreen() {
  return (
    <div className="guide-screen">
      <p className="guide-intro">바람따라는 이런 순서로 쓰면 편해요.</p>
      <ol className="guide-list">
        {SECTIONS.map((s) => (
          <li key={s.title} className="guide-item">
            <span className="guide-item-icon" aria-hidden="true">{s.icon}</span>
            <div>
              <h2 className="guide-item-title">{s.title}</h2>
              <p className="guide-item-body">{s.body}</p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
