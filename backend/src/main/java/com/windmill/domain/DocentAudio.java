package com.windmill.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "docent_audio", uniqueConstraints = @UniqueConstraint(columnNames = {"contentId", "language"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocentAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String contentId;

    /** "ko", "en", "ja", "zh" 등 */
    @Column(nullable = false)
    private String language;

    @Lob
    @Column(nullable = false)
    private String scriptText;

    /** TTS 실제 합성 결과 캐싱 경로 - 공급자 미정으로 현재는 null (스크립트만 제공) */
    private String audioUrl;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
