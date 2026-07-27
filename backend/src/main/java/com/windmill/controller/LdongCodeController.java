package com.windmill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 법정동 코드 조회 (ldongCode2) - 지역명 표시 및 신규 지역코드(lDongRegnCd/lDongSignguCd) 확인용.
 */
@RestController
@RequestMapping("/api/ldong-codes")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LdongCodeController {

    private final KorServiceClient korServiceClient;

    @GetMapping
    public Mono<ResponseEntity<List<JsonNode>>> get(
            @RequestParam(required = false) String lDongRegnCd,
            @RequestParam(defaultValue = "N") String listYn) {
        return korServiceClient.ldongCode(lDongRegnCd, listYn).map(ResponseEntity::ok);
    }
}
