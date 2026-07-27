package com.windmill.controller;

import com.windmill.dto.DocentAudioResponse;
import com.windmill.service.docent.DocentScriptService;
import lombok.RequiredArgsConstructor;
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
}
