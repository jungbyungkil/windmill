/**
 * 추천 결과가 AI 상상이 아니라 공공데이터로 검증됐음을 알리는 한 줄 배지.
 * 범용 LLM 챗봇과의 차별점(출처 있는 실시간 데이터) - 추천 화면 상단에 반복 노출.
 */
export default function TrustBadge() {
  return (
    <p className="trust-badge">
      🔍 AI가 지어낸 추천이 아니라, 한국관광공사·기상청 공공데이터로 실시간 검증된 장소예요
    </p>
  );
}
