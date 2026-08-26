package com.lingyao.platform.controller;

import com.lingyao.platform.config.PageableValidator;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.RegistrationRequest;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.service.PushService;
import com.lingyao.platform.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/registrations")
public class RegistrationController {

    @Autowired
    private RegistrationService service;

    @Autowired
    private PushService pushService;

    @PostMapping
    public ApiResponse<Registration> create(@Valid @RequestBody RegistrationRequest req) {
        // 创建端点公开（游客可注册），其他端点需鉴权
        try {
            Registration saved = service.create(req);
            // 异步推送（不阻塞报名）
            pushService.pushRegistrationAsync(saved);
            return ApiResponse.ok("报名提交成功，我们会在 24 小时内联系您", saved);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<Page<Registration>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        Pageable pageable = PageableValidator.safeOf(page, size);
        Page<Registration> result = service.list(status, pageable);
        return ApiResponse.ok(result);
    }

    @GetMapping("/stats")
    public ApiResponse<?> stats() {
        long pending = service.countByStatus("PENDING");
        long contacted = service.countByStatus("CONTACTED");
        long qualified = service.countByStatus("QUALIFIED");
        long closed = service.countByStatus("CLOSED");
        java.util.Map<String, Long> data = new java.util.LinkedHashMap<>();
        data.put("pending", pending);
        data.put("contacted", contacted);
        data.put("qualified", qualified);
        data.put("closed", closed);
        data.put("total", pending + contacted + qualified + closed);
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    public ApiResponse<Registration> get(@PathVariable Long id) {
        return ApiResponse.ok(service.findById(id));
    }

    /**
     * Bug-11 修复：状态流转改用 @RequestBody 而非 ?status=
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Registration> updateStatus(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.isEmpty()) {
            return ApiResponse.fail("status 字段必填");
        }
        Registration updated = service.updateStatus(id, newStatus);
        return ApiResponse.ok("状态已更新", updated);
    }
}
