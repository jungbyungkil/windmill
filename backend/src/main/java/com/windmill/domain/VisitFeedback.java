package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

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
}
