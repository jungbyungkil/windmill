package com.windmill.dto;

import com.windmill.domain.VisitRating;
import lombok.Data;

import java.util.List;

/**
 * 여행 기록(일기) 수정 - itineraryId/rerouteCount는 시스템이 정하는 값이라 수정 대상에서 제외한다.
 * visitFeedback은 항상 통째로 교체(삭제 후 재생성) - VisitFeedback.id를 외부에서 참조하는 곳이
 * 없어(CommunityScheduleService도 컬렉션을 그대로 순회할 뿐) 안전하다.
 */
@Data
public class UpdateTripRecordRequest {
    private String overallNote;
    private VisitRating overallRating;
    private List<VisitFeedbackRequest> visitFeedback;
}
