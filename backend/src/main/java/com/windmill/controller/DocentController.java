package com.windmill.controller;

import com.windmill.dto.DocentAudioResponse;
import com.windmill.service.docent.DocentScriptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/docent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocentController {

    private final DocentScriptService docentScriptService;

    @GetMapping("/{contentId}")
    public Mono<ResponseEntity<DocentAudioResponse>> get(
            @PathVariable String contentId,
            @RequestParam int contentTypeId,
            @RequestParam(defaultValue = "ko") String lang) {
        return docentScriptService.getOrGenerate(contentId, contentTypeId, lang)
                .map(DocentAudioResponse::from)
                .map(ResponseEntity::ok);
    }

    /** 자체 합성(OpenAI TTS) 오디오 바이트 스트리밍 - DocentAudioResponse.audioUrl이 가리키는 경로 */
    @GetMapping(value = "/audio/{id}", produces = "audio/mpeg")
    public Mono<ResponseEntity<byte[]>> audio(@PathVariable Long id) {
        return docentScriptService.getAudioBytes(id)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.valueOf("audio/mpeg")).body(bytes))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
