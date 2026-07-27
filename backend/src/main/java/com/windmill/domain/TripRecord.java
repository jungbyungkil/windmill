package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionUuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id")
    private Itinerary itinerary;

    @Column(length = 2000)
    private String overallNote;

    /** 바람개비 트리거로 대안 제안된 횟수 */
    @Builder.Default
    @Column(nullable = false)
    private int rerouteCount = 0;

    // EAGER: open-in-view=false + 트랜잭션 밖 DTO 변환 조합에서 LAZY면 LazyInitializationException 발생 (Itinerary.items 참고)
    @Builder.Default
    @OneToMany(mappedBy = "tripRecord", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<VisitFeedback> visitFeedback = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (completedAt == null) {
            completedAt = LocalDateTime.now();
        }
    }
}
