package com.windmill.repository;

import com.windmill.domain.DocentAudio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocentAudioRepository extends JpaRepository<DocentAudio, Long> {
    Optional<DocentAudio> findByContentIdAndLanguage(String contentId, String language);
}
