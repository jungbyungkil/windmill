package com.windmill.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * react-router-dom(BrowserRouter) 도입으로 /trip, /smart-plan 같은 프론트 라우트를 새로고침하거나
 * 직접 URL로 열면 정적 리소스가 아니라 서버가 처리하게 되어 그대로면 404가 난다.
 * API(/api/**, 항상 다단계 경로)와 정적 파일(점 포함 경로)은 제외하고 단일 세그먼트 경로만
 * index.html로 포워딩해 클라이언트 라우터가 이어받게 한다.
 */
@Controller
public class SpaController {

    @RequestMapping("/{path:[^.]+}")
    public String forward() {
        return "forward:/index.html";
    }
}
