package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 성인 대표 연령대 - companionType과 별개 축(동반유형은 인원구성, 이건 나이대). 신규 컬럼이지만
     * companionType과 동일하게 nullable로 둔다(NOT NULL로 걸면 이미 행이 있는 prod 테이블에서
     * ALTER TABLE이 실패함 - "prod DB 컬럼 누락 사고" 교훈). 필수 여부는 CreateItineraryRequest의
     * @NotNull(애플리케이션 레벨)로만 강제.
     */
    @Enumerated(EnumType.STRING)
    private AgeGroup adultAgeGroup;

    /**
     * 동반 자녀 만 나이(0~17) - 성인과 달리 연령대가 아니라 정확한 나이를 받는다(추천 시 실제
     * expAgeRange 하한과 직접 비교하기 위함, AgeGroupRanking 참고). 새 테이블이라 NOT NULL
     * 컬럼 추가 위험 없음(빈 컬렉션이 기본값).
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "itinerary_child_ages", joinColumns = @JoinColumn(name = "itinerary_id"))
    @Column(name = "child_age")
    private List<Integer> childAges = new ArrayList<>();

    @Builder.Default
    @Column(nullable = false)
    private boolean withPet = false;

    /**
     * 전체 인원수 - 슬롯별/전체 예상 비용 합산의 곱셈 기준(1인 요금 × partySize). companionType은 라벨용
     * enum이라 정확한 인원수를 담지 못해 별도 컬럼으로 추가한다. ColumnDefault: 이미 행이 있는 테이블에
     * NOT NULL 컬럼을 추가할 때 기존 행을 1명으로 채우기 위함(strollerFriendly와 동일 이유).
     */
    @Builder.Default
    @ColumnDefault("1")
    @Column(nullable = false)
    private int partySize = 1;

    /**
     * 유모차 동반 - true면 추천 파이프라인이 유모차 이용 가능 여부를 후보 순위에 반영한다.
     * ColumnDefault: 이미 행이 있는 테이블에 NOT NULL 컬럼을 추가할 때 기존 행을 false로 채우기 위함
     * (isAlternate와 동일 이유 - 없으면 프로덕션에서 ALTER TABLE 자체가 실패한다).
     */
    @Builder.Default
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean strollerFriendly = false;

    /** 무장애(장애인 동반) - 구조화된 API 필드가 없어 키워드 매칭 휴리스틱으로 후보 순위에 반영한다 */
    @Builder.Default
    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean accessibleFriendly = false;

    // EAGER: open-in-view=false라 트랜잭션(서비스 메서드) 밖에서 DTO로 변환하는 컨트롤러 계층에서
    // LAZY 컬렉션에 접근하면 LazyInitializationException이 발생함 - 일정당 항목 수가 적어 EAGER가 안전
    @Builder.Default
    @OneToMany(mappedBy = "itinerary", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("displayOrder ASC")
    private List<ItineraryItem> items = new ArrayList<>();

    /** 일자별 페이지 확정 - "확정"된 날짜만 담김. EAGER: Itinerary.items와 동일한 이유(트랜잭션 밖 DTO 변환) */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "itinerary_confirmed_dates", joinColumns = @JoinColumn(name = "itinerary_id"))
    @Column(name = "confirmed_date")
    private Set<LocalDate> confirmedDates = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 일정 공유용 공개 토큰 - 생성 전까지 null */
    @Column(unique = true, length = 64)
    private String shareToken;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
