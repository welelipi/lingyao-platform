package com.lingyao.platform.controller;

import com.lingyao.platform.config.PageableValidator;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.RegistrationRequest;
import com.lingyao.platform.entity.Registration;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
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

    // ──────────────── V2.0.78.27 安全（L-01）守卫 ────────────────

    /**
     * 仅允许平台超管访问报名 PII 接口；非超管返回 403。
     *
     * 与 AdminController#requirePlatformAdmin / ReleaseController#requirePlatformAdmin
     * 同名同实现（本项目未抽公共 Guard 组件，惯例为各 Controller 私有副本）。
     *
     * 为什么必须补：SecurityConfig 原先把 "/api/registrations" 放进公开白名单且
     * 不带方法限定，导致 GET 列表 / GET /{id} / PUT /{id}/status 三个含
     * 姓名·手机·邮箱·意向产品的端点一并 permitAll —— 零 header / 零 cookie
     * 即可拉全站报名 PII。SecurityConfig 侧已收紧为「精确方法匹配」，
     * 但授权判定不应只依赖一处配置，故在 Controller 内再做一次超管校验（纵深防御）。
     */
    private ApiResponse<?> requirePlatformAdmin() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return ApiResponse.fail(401, "未登录");
        if (!cu.isPlatformAdmin()) return ApiResponse.fail(403, "仅平台超管可访问该接口");
        return null;
    }

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
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
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
        // V2.0.64：补 CONVERTED（终态）——之前漏斗缺最后一环
        long converted = service.countByStatus("CONVERTED");
        java.util.Map<String, Long> data = new java.util.LinkedHashMap<>();
        data.put("pending", pending);
        data.put("contacted", contacted);
        data.put("qualified", qualified);
        data.put("closed", closed);
        data.put("converted", converted);
        data.put("total", pending + contacted + qualified + closed + converted);
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    public ApiResponse<Registration> get(@PathVariable Long id) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        return ApiResponse.ok(service.findById(id));
    }

    /**
     * Bug-11 修复：状态流转改用 @RequestBody 而非 ?status=
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Registration> updateStatus(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());
        String newStatus = body.get("status");
        if (newStatus == null || newStatus.isEmpty()) {
            return ApiResponse.fail("status 字段必填");
        }
        Registration updated = service.updateStatus(id, newStatus);
        return ApiResponse.ok("状态已更新", updated);
    }
}
