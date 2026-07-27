package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

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

    /** "09:00" 같은 시간 문자열 - 프론트에서 형식 통제 */
    private String scheduledTime;

    @Builder.Default
    @ElementCollection
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
