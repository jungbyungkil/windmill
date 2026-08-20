import { useNavigate } from 'react-router-dom';

const PAGE1_STEPS = [
  { icon: '📍', title: '여행 지역 선택', body: '시/도 → 시/군/구 순서로 다녀올 지역을 골라요.' },
  { icon: '📅', title: '여행 날짜 선택', body: '바람따라는 당일치기만 지원해요. 하루 날짜만 고르면 돼요.' },
  { icon: '🧑‍🤝‍🧑', title: '누구와 함께하나요?', body: '1인·2인·4인 가족·대가족 중 고르고, 필요하면 반려동물·유모차 동반·무장애 이동 옵션도 함께 체크해요.' },
];

const PAGE1_BRANCH = [
  {
    tag: 'A',
    icon: '📖',
    title: '당일치기 추천 기록 참고',
    body: '다른 여행자가 다녀온 일정 카드를 보고 "이 일정으로 시작"을 누르면, 같은 장소·순서 그대로 내 일정으로 복제돼요.',
  },
  {
    tag: 'B',
    icon: '🌬️',
    title: '"당일치기 시작하기" 버튼',
    body: '혼잡이 덜하고 동선이 짧은 스마트 동선을 새로 만들어 자동으로 담아드려요.',
  },
];

// "고정 일정(앵커) 등록" 문구는 CreateTripScreen의 실제 UI 문구를 그대로 재사용(일관성 유지)
const ANCHOR_STEPS = [
  { icon: '📌', title: '앵커 등록', body: 'DDP 공연처럼 시각이 이미 정해진 장소가 있다면 이름으로 찾아 등록해요. 시작 시각만 정하면 돼요.' },
  { icon: '⏱️', title: '앞뒤 시간 자동 채움', body: '등록한 시각을 기준으로 앞뒤 빈 시간(식사·가벼운 관광)을 자동으로 채워, 바로 시작할 수 있게 준비해요.' },
  { icon: '📍', title: '근처 추천 코스 확인', body: '앵커 장소를 기준으로 이동 가능한 반경 안에서 어울리는 코스를 자동으로 구성해 보여줘요.' },
];

// 색상/상태명/의미는 PinwheelHero의 LEVEL_META(NORMAL/WARNING/DANGER)와 1:1로 일치시킴 - 문구가 실제 트리거 로직과 어긋나지 않도록 유지
const PINWHEEL_LEVELS = [
  { level: 'normal', emoji: '🟢', name: '순풍', meaning: '계획대로 진행 가능, 이상 없음' },
  { level: 'warning', emoji: '🟡', name: '주의', meaning: '경미한 이슈 발생, 참고 필요' },
  { level: 'danger', emoji: '🔴', name: '변경 필요', meaning: '기존 계획 진행 불가, 대응 필요' },
];

const PAGE2_STEPS = [
  {
    icon: '🧭',
    title: '스마트 동선으로 시작 또는 직접 구성',
    body: '자동으로 담긴 스마트 동선을 그대로 쓰거나, 마음에 안 들면 하나씩 지우고 직접 장소를 골라 채울 수 있어요.',
  },
  {
    icon: '🏷️',
    title: '태그 선택 → 추천받기',
    body: '#자연 #실내 #맛집 #아이동반 #액티비티 #역사 중 원하는 태그를 고르고 "추천받기"를 누르면 어울리는 장소 목록이 떠요. 마음에 드는 곳을 일정에 담고 방문 시간을 정해요.',
  },
  {
    icon: '🌀',
    title: '실제 여행 진행 - 바람개비',
    body: '일정 화면 위쪽 바람개비가 지금 날씨·혼잡·영업 상황을 알려줘요. 비/폭염/혼잡이 감지되면 대안 코스를 추천받거나, 영향받은 장소만 자동으로 바꿀 수 있어요.',
  },
  {
    icon: '✅',
    title: '여행 마무리 작성(완료 기록)',
    body: '다녀온 뒤 "여행 마무리"에서 별점과 후기를 남기면, 같은 지역을 찾는 다른 여행자의 추천 기록으로 쓰여요. "일정 공유"로 링크를 만들어 함께 가는 사람에게 미리 보낼 수도 있어요.',
  },
];

function StepList({ steps, startNumber = 1 }) {
  return (
    <ol className="guide-list" start={startNumber}>
      {steps.map((s, i) => (
        <li key={s.title} className="guide-item">
          <span className="guide-item-icon" aria-hidden="true">{s.icon}</span>
          <div>
            <h3 className="guide-item-title">{startNumber + i}. {s.title}</h3>
            <p className="guide-item-body">{s.body}</p>
          </div>
        </li>
      ))}
    </ol>
  );
}

/** 전체 메뉴 > 이용 가이드 - 실제 화면 흐름(PAGE1 온보딩 → PAGE2 여행 진행) 그대로 정리 + 버튼으로 바로 따라 해볼 수 있게 함 */
export default function GuideScreen() {
  const navigate = useNavigate();

  return (
    <div className="guide-screen">
      <p className="guide-intro">바람따라는 이런 순서로 쓰면 편해요. 버튼을 누르면 그 자리에서 바로 따라 해볼 수 있어요.</p>

      <section className="guide-trust-section">
        <h2 className="guide-trust-title">바람따라는 AI 챗봇이 아니에요</h2>
        <ul className="guide-trust-list">
          <li>
            <span className="guide-trust-icon" aria-hidden="true">🔍</span>
            <span>지금 이 순간의 혼잡도·영업시간은 한국관광공사·기상청 공식 데이터로만 확인해요.
              "보통 주말엔 붐빕니다" 같은 짐작이 아니에요.</span>
          </li>
          <li>
            <span className="guide-trust-icon" aria-hidden="true">🛡️</span>
            <span>공공데이터로 걸러낸 후보만 추천해요. AI는 마지막 한 문장을 다듬을 뿐, 없는 장소를
              지어내지 않아요.</span>
          </li>
          <li>
            <span className="guide-trust-icon" aria-hidden="true">🌬️</span>
            <span>물어봐야 답하는 챗봇과 달리, 위치·시간·날씨가 바뀌면 앱이 먼저 알아채고 알려드려요.</span>
          </li>
        </ul>
      </section>

      <section className="guide-page">
        <h2 className="guide-page-title">
          <span className="guide-page-badge">PAGE 1</span>
          여행 준비 (온보딩)
        </h2>
        <StepList steps={PAGE1_STEPS} startNumber={1} />

        <div className="guide-branch">
          <span className="guide-branch-label">4. 여기서 둘 중 하나를 골라요</span>
          <div className="guide-branch-options">
            {PAGE1_BRANCH.map((b) => (
              <div key={b.tag} className="guide-item guide-branch-item">
                <span className="guide-item-icon" aria-hidden="true">{b.icon}</span>
                <div>
                  <h3 className="guide-item-title">
                    <span className="guide-branch-tag">{b.tag}</span>
                    {b.title}
                  </h3>
                  <p className="guide-item-body">{b.body}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="guide-subsection">
          <h3 className="guide-subsection-title">📌 이미 정해진 일정이 있다면 (앵커 등록)</h3>
          <p className="guide-subsection-body">
            이미 정해진 일정이 있어요? 앵커로 등록해두면 앞뒤 빈 시간을 자동으로 채워드려요.
          </p>
          <StepList steps={ANCHOR_STEPS} startNumber={1} />
        </div>

        <button type="button" className="btn-primary guide-cta" onClick={() => navigate('/')}>
          🌬️ 여기서부터 따라 해보기
        </button>
      </section>

      <section className="guide-page">
        <h2 className="guide-page-title">
          <span className="guide-page-badge">PAGE 2</span>
          여행 진행
        </h2>
        <StepList steps={PAGE2_STEPS} startNumber={5} />

        <div className="guide-subsection">
          <h3 className="guide-subsection-title">🌀 바람개비 색상, 무슨 뜻일까요?</h3>
          <p className="guide-subsection-body">
            바람개비는 여행 중에도 날씨·혼잡도·동선을 주기적으로 확인해서, 상황이 바뀌면 색으로 바로 알려드려요.
          </p>
          <table className="guide-status-table">
            <thead>
              <tr>
                <th scope="col">색상</th>
                <th scope="col">상태명</th>
                <th scope="col">의미</th>
              </tr>
            </thead>
            <tbody>
              {PINWHEEL_LEVELS.map((s) => (
                <tr key={s.level}>
                  <td>
                    <span className={`guide-status-dot level-${s.level}`} aria-hidden="true" />
                    {s.emoji}
                  </td>
                  <td>{s.name}</td>
                  <td>{s.meaning}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="guide-subsection-body">
            바람개비를 탭하면 색이 바뀐 사유를 확인하고, 이어서 대응 방안(대안 코스)까지 바로 볼 수 있어요.
          </p>
          <div className="guide-callout">
            🔍 이 판단은 AI의 임의 추측이 아니에요. 기상청·한국관광공사 공공데이터로 실시간 검증한 결과예요.
          </div>
        </div>

        <button type="button" className="btn-primary guide-cta" onClick={() => navigate('/')}>
          🧭 내 일정에서 이어서 따라 해보기
        </button>
        <p className="guide-cta-hint">진행 중인 일정이 있으면 바로 그 일정으로, 없으면 새 여행 시작 화면으로 이동해요.</p>
      </section>
    </div>
  );
}
