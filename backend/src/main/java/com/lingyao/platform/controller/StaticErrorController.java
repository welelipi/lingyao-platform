package com.lingyao.platform.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 静态资源 404/500 错误处理：
 * - 404 路径 → 转发到 /404.html
 * - 500 路径 → 转发到 /404.html（避免直接输出 JSON 错误）
 * - API 路径（/api/*）→ 仍然返回 JSON ApiResponse
 */
@Controller
public class StaticErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    public Object handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status != null ? (int) status : 500;

        // API 路径走 JSON
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return ResponseEntity.status(code)
                .body("{\"code\":" + code + ",\"message\":\"接口不存在或已下线\",\"success\":false}");
        }

        // 静态资源 404 → 转发到 404.html
        if (code == HttpStatus.NOT_FOUND.value()) {
            return "forward:/404.html";
        }

        // 500 等其他错误 → 转发到 404.html（避免直接抛 NoResourceFoundException）
        return "forward:/404.html";
    }
}
