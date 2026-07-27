package com.windmill.dto;

import com.windmill.domain.DocentAudio;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocentAudioResponse {
    private String contentId;
    private String language;
    private String scriptText;
    private String audioUrl;

    public static DocentAudioResponse from(DocentAudio audio) {
        return DocentAudioResponse.builder()
                .contentId(audio.getContentId())
                .language(audio.getLanguage())
                .scriptText(audio.getScriptText())
                .audioUrl(audio.getAudioUrl())
                .build();
    }
}
