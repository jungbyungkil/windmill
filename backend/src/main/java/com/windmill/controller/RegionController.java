package com.windmill.controller;

import com.windmill.dto.RegionSidoGroup;
import com.windmill.service.region.RegionCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 전국 시/도-시/군/구 목록 - 프론트 첫 화면 지역 캐스케이딩 드롭다운 데이터 소스 */
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RegionController {

    private final RegionCodeService regionCodeService;

    @GetMapping
    public List<RegionSidoGroup> list() {
        return regionCodeService.tree();
    }
}
