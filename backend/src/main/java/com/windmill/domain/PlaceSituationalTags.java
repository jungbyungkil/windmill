package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 자체 생성 상황 메타데이터. TourAPI 원본이 아니라 규칙/수동 보정 결과라
 * "관광 데이터 미저장" 원칙의 예외로 저장한다.
 */
@Entity
@Table(name = "place_situational_tags")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceSituationalTags {

    /** TourAPI contentId */
    @Id
    @Column(name = "place_id", nullable = false, length = 40)
    private String contentId;

    /** true=실내, false=실외 */
    @Column(name = "indoor_yn")
    private Boolean indoorYn;

    @Enumerated(EnumType.STRING)
    @Column(name = "rain_sensitivity", length = 20)
    private RainSensitivity rainSensitivity;

    @Enumerated(EnumType.STRING)
    @Column(name = "congestion_sensitivity", length = 20)
    private CongestionSensitivity congestionSensitivity;

    @Enumerated(EnumType.STRING)
    @Column(name = "inferred_source", nullable = false, length = 20)
    private InferredSource inferredSource;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
