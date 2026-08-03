package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "itinerary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionUuid;

    /** region-codes.json의 signguFullCode (예: "51210" = 강원특별자치도 속초시) */
    @Column(nullable = false)
    private String signguFullCode;

    /** "강원특별자치도 속초시" 같은 표시용 지역명 - 생성 시점에 RegionCode에서 스냅샷 */
    @Column(nullable = false)
    private String regionDisplayName;

    /** 생성 시점 RegionCode.weatherNx/Ny 스냅샷 - 매 응답마다 지역코드 재조회하지 않도록 저장 */
    private String weatherNx;
    private String weatherNy;

    private LocalDate startDate;
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private CompanionType companionType;

    @Builder.Default
    @Column(nullable = false)
    private boolean withPet = false;

    // EAGER: open-in-view=false라 트랜잭션(서비스 메서드) 밖에서 DTO로 변환하는 컨트롤러 계층에서
    // LAZY 컬렉션에 접근하면 LazyInitializationException이 발생함 - 일정당 항목 수가 적어 EAGER가 안전
    @Builder.Default
    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("displayOrder ASC")
    private List<ItineraryItem> items = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
