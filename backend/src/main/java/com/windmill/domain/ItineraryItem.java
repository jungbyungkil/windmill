package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "itinerary_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;

    @Column(nullable = false)
    private String contentId;

    /** detailIntro2 등 타입별 응답 조회에 필요 (12: 관광지, 14: 문화시설, 15: 축제/행사 등) */
    private Integer contentTypeId;

    @Column(nullable = false)
    private String placeName;

    /** firstimage - 없으면 null(프론트에서 플레이스홀더 표시) */
    private String thumbnailUrl;

    /** TourAPI mapx/mapy - 동선 꼬임 감지·자동 재배치에 사용 */
    private String mapX;
    private String mapY;

    /** "09:00" 같은 시간 문자열 - 프론트에서 형식 통제 */
    private String scheduledTime;

    /** 일자별 페이지 구분 기준. 담을 때 미지정이면 ItineraryService가 itinerary.startDate로 채운다 */
    private LocalDate visitDate;

    // 아래 4개는 담는 시점의 RecommendationCandidate 스냅샷 - thumbnailUrl/crowdRate와 같은 패턴(재조회 없이 카드에 표시)
    private String addr1;
    private String tel;
    private String useFeeText;
    private Boolean isFree;
    private String restDateText;

    /** RecommendationCandidate.category 스냅샷 - 여행기록 집계(CommunityScheduleService)에서 슬롯별 카테고리 표시에 사용 */
    private String category;
    /** "자동 재배치"/"대안 추천" 경로로 담겼는지 - 여행기록에 그대로 스냅샷되어 실시간 대응 사례로 집계됨.
     *  ColumnDefault: 이미 행이 있는 테이블에 NOT NULL 컬럼을 추가할 때 기존 행을 false로 채우기 위함 */
    @Builder.Default
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean isAlternate = false;

    // EAGER: open-in-view=false + 트랜잭션 밖 DTO 변환 조합에서 LAZY면 LazyInitializationException 발생 (Itinerary.items 참고)
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "itinerary_item_tags", joinColumns = @JoinColumn(name = "itinerary_item_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    /** 집중률 API 원본값 (가공 전). 매칭 실패/미조회 시 null */
    private Double crowdRate;

    @Builder.Default
    @Column(nullable = false)
    private boolean isPinned = false;

    private String pinnedReason;

    @Builder.Default
    @Column(nullable = false)
    private int displayOrder = 0;
}
