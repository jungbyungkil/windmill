import { useNavigate } from 'react-router-dom';

const PAGE1_STEPS = [
  { icon: '📍', title: '여행 지역 선택', body: '시/도 → 시/군/구 순서로 다녀올 지역을 골라요.' },
  { icon: '📅', title: '여행 날짜 선택', body: '바람따라는 당일치기만 지원해요. 하루 날짜만 고르면 되고, 지난날은 고를 수 없어요. 같은 날 진행 중인 여행이 있으면 이어하기나 새로 만들기를 고를 수 있어요.' },
  { icon: '🧑‍🤝‍🧑', title: '누구와 함께하나요?', body: '1인·2인·3인·4인 가족·대가족 중 고르고, 성인 연령대를 골라요. 자녀가 있으면 나이를 넣고, 반려동물·유모차 동반·무장애 이동이 필요하면 함께 체크해요.' },
];

const PAGE1_BRANCH = [
  {
    tag: 'A',
    icon: '🌬️',
    title: '스마트 동선으로 시작',
    body: '큰 버튼 하나로 오전 일정 → 점심 → 오후 일정 → 저녁 리듬을 채워 드려요. 그 시/군/구의 축제와 인기 스팟을 오전·오후에 두고(인기 1위와 그날 축제는 오전에), 점심·저녁은 인기 맛집으로 넣어요. 방문일이 휴무이거나 마감에 닿는 곳은 빼고, 그 지역에 열리는 축제가 없으면 인기 장소로 채워요.',
  },
  {
    tag: 'B',
    icon: '📖',
    title: '추천 코스',
    body: '아래에 「📖 추천 코스 또는 📌 직접 선택」을 연 뒤, 다녀온 사람이 남긴 당일치기 카드에서 「이 일정으로 시작」을 누르면 같은 장소·순서 그대로 복제돼요.',
  },
  {
    tag: 'C',
    icon: '📌',
    title: '직접 선택',
    body: '같은 칸에서 공연·예약처럼 시각이 정해진 장소를 이름으로 찾아 시작 시각만 정하면 돼요. 그 장소를 기준으로 앞뒤 빈 시간(식사·가벼운 관광)을 근처에서 채워 드려요.',
  },
];

const PINWHEEL_LEVELS = [
  { level: 'normal', emoji: '🟢', name: '순풍', meaning: '계획대로 진행 가능, 이상 없음' },
  { level: 'warning', emoji: '🟡', name: '주의', meaning: '경미한 이슈 발생, 참고 필요' },
  { level: 'danger', emoji: '🔴', name: '변경 필요', meaning: '기존 계획 진행 불가, 대응 필요' },
];

const PAGE2_STEPS = [
  {
    icon: '🧭',
    title: '홈 · 지도 · 검색 · 알림 · 프로필',
    body: '위쪽 「← 메인」을 누르면 여행 만들기 화면으로 돌아가요. 일정 화면은 아래 탭으로 다섯 구간을 오가요. 홈에서 오늘 일정을 보고, 지도에서 동선을, 검색에서 장소를 더 담고, 알림에서 온 메시지와 오늘 날씨·중기 예보를, 프로필에서 글씨·알림·마무리를 다뤄요.',
  },
  {
    icon: '📋',
    title: '일정 카드 다루기',
    body: '장소 이름을 누르면 상세가 펼쳐져요. 왼쪽 시각을 눌러 방문 시간을 바꾸고, 「⏱ 시간순 정렬」로 목록 순서를 맞출 수 있어요. 펼친 카드에서는 🗺️ 지도 · ✏️ 수정 · 📌 고정 · 🎧 도슨트 · 🗑️ 삭제를 쓸 수 있어요. 고정은 검색의 근처 기준 기본값만 바꿔 줘요.',
  },
  {
    icon: '🏷️',
    title: '장소 더 담기',
    body: '검색 탭에서 「근처 기준」으로 오늘 일정 중 한 곳을 고른 뒤, 맛집·카페 / 실내 / 역사·전통 / 자연·액티비티 / 쇼핑·체험 태그를 고르고 「추천받기」를 눌러요. 맛집이면 1인 식사 참고 금액을, 그 외에는 무료 장소만 보기를 켤 수 있어요. 지도에서는 옮긴 뒤 「이 지역 재검색」으로 주변을 담아요. 방문일이 휴무이거나 마감에 가까우면 「그래도 담기 / 취소」로 물어보고, 다른 일정과 시간이 겹치면 담지 않아요.',
  },
  {
    icon: '🌀',
    title: '변수에 바로 대응하기',
    body: '홈 위쪽 바람개비와 「지금 변수에 대응하기」 세 장(비·폭염 / 혼잡 / 동선)이 여행 당일 이미 담은 일정을 90초마다 다시 봐요. 반짝이면 실내·한산한 곳·짧은 동선으로 바로 바꿀 수 있어요. 휴무·마감·이동시간 부족도 바람개비가 잡아 주고, 「동선 다시」로 순서를 다시 짤 수 있어요. 이렇게 바꾼 일정은 「🕓 변경 이력」에 쌓여서, 맨 처음 짠 원본과 비교하거나 예전 시점으로 되돌릴 수 있어요(되돌리기도 이력에 남아요). 바뀐 장소 카드엔 「🟡 변경됨」 표시가 붙어요.',
  },
  {
    icon: '✅',
    title: '알림 · 마무리 · 공유',
    body: '프로필에서 알림을 켜 두면 여행 당일에만 와요. 순풍일 때는 첫 일정 30분 전과 여행 마무리를, 주황·빨강(비·폭염·혼잡·동선·휴무)은 감지되는 즉시 휴대폰으로 알려드려요. 글씨 크기도 여기서 키울 수 있어요. 「여행 마무리」는 좋았음·보통·별로만 누르면 되고, 별로인 장소는 다음 추천에서 빼 드려요. 좋았음이면 같은 지역 추천 코스로 쓰일 수 있어요. 홈의 「공유」로 링크를 보내고, 메뉴의 「내 여행 관리」에서 지난 일정을 볼 수 있어요.',
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

/** 전체 메뉴 > 이용 가이드 - 실제 화면 흐름(준비 → 당일) 그대로 정리 */
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
              「보통 주말엔 붐빕니다」 같은 짐작이 아니에요.</span>
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
          여행 준비
        </h2>
        <StepList steps={PAGE1_STEPS} startNumber={1} />

        <div className="guide-branch">
          <span className="guide-branch-label">4. 여기서 셋 중 하나를 골라요</span>
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

        <button type="button" className="btn-primary guide-cta" onClick={() => navigate('/')}>
          🌬️ 여기서부터 따라 해보기
        </button>
      </section>

      <section className="guide-page">
        <h2 className="guide-page-title">
          <span className="guide-page-badge">PAGE 2</span>
          여행 당일
        </h2>
        <StepList steps={PAGE2_STEPS} startNumber={5} />

        <div className="guide-subsection">
          <h3 className="guide-subsection-title">🌀 바람개비 색상, 무슨 뜻일까요?</h3>
          <p className="guide-subsection-body">
            바람개비는 여행 당일에 날씨·혼잡·동선·휴무·영업시간을 90초마다 확인해서, 다음 장소 전에 색으로 알려드려요.
          </p>
          <div className="guide-status-legend">
            {PINWHEEL_LEVELS.map((s) => (
              <div className="guide-status-row" key={s.level}>
                <span className={`guide-status-dot level-${s.level}`} aria-hidden="true" />
                <span className="guide-status-name">{s.name}</span>
                <span className="guide-status-meaning">{s.meaning}</span>
              </div>
            ))}
          </div>
          <p className="guide-subsection-body">
            바람개비를 탭하면 색이 바뀐 사유를 확인하고, 이어서 대응(실내 일정 · 다른 장소 · 동선 다시)까지 바로 볼 수 있어요.
          </p>
          <div className="guide-callout">
            🔍 이 판단은 AI의 임의 추측이 아니에요. 기상청·한국관광공사 공공데이터로 실시간 검증한 결과예요.
          </div>
        </div>

        <button type="button" className="btn-primary guide-cta" onClick={() => navigate('/trip')}>
          🧭 내 일정에서 이어서 따라 해보기
        </button>
        <p className="guide-cta-hint">진행 중인 일정이 있으면 바로 그 일정으로, 없으면 새 여행 시작 화면으로 이동해요.</p>
      </section>
    </div>
  );
}
