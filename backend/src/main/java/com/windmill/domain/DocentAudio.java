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

    /** 오디(Odii) 공식 콘텐츠일 때만 채워지는 외부 오디오 URL - 자체 합성분은 audioData에 저장하고 이 필드는 null */
    private String audioUrl;

    /** OpenAI TTS로 자체 합성한 오디오 바이트(mp3) - DocentController가 /api/docent/audio/{id}로 스트리밍 */
    @Lob
    private byte[] audioData;

    @Column(nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
