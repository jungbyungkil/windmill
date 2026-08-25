/** 일정에 담을 때 함께 넘기는 TourAPI 스냅샷·상황 추론 입력값 */
export function placeSnapshotFields(place = {}) {
  return {
    overview: place.overview || undefined,
    detailFacts: Array.isArray(place.detailFacts) && place.detailFacts.length > 0
      ? place.detailFacts
      : undefined,
    cat3: place.cat3 || undefined,
  };
}
