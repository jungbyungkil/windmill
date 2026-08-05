package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "visit_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_record_id", nullable = false)
    private TripRecord tripRecord;

    /** ItineraryItem.id 참조 */
    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private String placeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitRating rating;

    private String memo;

    // 아래는 TripRecordService.create()가 itemId로 원본 ItineraryItem을 찾아 스냅샷한다(프론트가 보내지 않음) -
    // CommunityScheduleService의 지역별 일자·시간대 집계에 쓰인다.
    private String contentId;
    private Integer contentTypeId;
    private String category;
    /** 여행 시작일 기준 며칠째인지 (1부터 시작). ColumnDefault: 기존 행이 있는 테이블에 NOT NULL 컬럼 추가 시 필요 */
    @ColumnDefault("1")
    private int dayNo;
    /** "오전" | "점심" | "오후" | "저녁" - scheduledTime 버킷팅 결과 */
    private String timeSlot;
    @ColumnDefault("false")
    private boolean isAlternate;
}
