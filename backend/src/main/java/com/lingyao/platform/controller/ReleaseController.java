package com.lingyao.platform.controller;

import com.lingyao.platform.config.PageableValidator;
import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.dto.admin.DeployStagingRequest;
import com.lingyao.platform.entity.ReleaseHistory;
import com.lingyao.platform.repository.ReleaseHistoryRepository;
import com.lingyao.platform.security.CurrentUser;
import com.lingyao.platform.security.JwtAuthFilter;
import com.lingyao.platform.service.ReleaseService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布管理 API — V2.0.5 R-7
 *
 * 端点：
 * - GET  /api/admin/release/status         staging/prod 当前版本 + 是否有部署运行中
 * - POST /api/admin/release/deploy-staging 触发 staging 部署（仅 platform_admin）
 * - POST /api/admin/release/deploy-prod    触发 prod 晋升（仅 platform_admin）
 * - GET  /api/admin/release/history        历史记录（分页，按环境过滤）
 * - GET  /api/admin/release/{id}           单条历史详情（用于查看 log）
 *
 * 鉴权：所有端点要求 platformAdmin=true（非平台超管返回 403）
 *
 * 注意：deploy-staging / deploy-prod 是异步执行，立即返回 historyId
 *       客户端轮询 /api/admin/release/{id} 或 /api/admin/release/history 看状态
 */
@RestController
@RequestMapping("/api/admin/release")
public class ReleaseController {

    @Autowired private ReleaseService releaseService;
    @Autowired private ReleaseHistoryRepository historyRepo;

    // ──────────── 鉴权 ────────────

    private ApiResponse<?> requirePlatformAdmin() {
        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        if (cu == null) return ApiResponse.fail(401, "未登录");
        if (!cu.isPlatformAdmin()) return ApiResponse.fail(403, "仅平台超管可访问该接口");
        return null;
    }

    // ──────────── 状态查询 ────────────

    /**
     * 获取当前版本 + 运行状态
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());

        Map<String, Object> data = new LinkedHashMap<>();

        // staging 当前版本
        ReleaseHistory stagingLatest = releaseService.getCurrentVersion(ReleaseHistory.ReleaseEnv.STAGING);
        data.put("staging", stagingLatest == null ? null : Map.of(
                "version", stagingLatest.getVersion(),
                "deployed_by", stagingLatest.getDeployedBy(),
                "finished_at", stagingLatest.getFinishedAt()
        ));

        // prod 当前版本
        ReleaseHistory prodLatest = releaseService.getCurrentVersion(ReleaseHistory.ReleaseEnv.PROD);
        data.put("prod", prodLatest == null ? null : Map.of(
                "version", prodLatest.getVersion(),
                "deployed_by", prodLatest.getDeployedBy(),
                "finished_at", prodLatest.getFinishedAt()
        ));

        // 是否有部署在跑
        data.put("running", releaseService.hasRunningDeployment());

        return ApiResponse.ok(data);
    }

    // ──────────── 触发部署 ────────────

    /**
     * 触发 staging 部署
     */
    @PostMapping("/deploy-staging")
    public ApiResponse<Map<String, Object>> deployStaging(@Valid @RequestBody DeployStagingRequest req) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());

        // 防并发：如果已有部署在跑，拒绝
        if (releaseService.hasRunningDeployment()) {
            return ApiResponse.fail(409, "已有部署正在执行，请等待完成后再试");
        }

        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        Long historyId = releaseService.initStagingDeploy(req.getJarPath(), cu.getUserId());
        releaseService.executeStagingDeploy(historyId, req.getJarPath());  // 异步，不阻塞

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("historyId", historyId);
        data.put("message", "staging 部署已启动，请通过 /api/admin/release/history 跟踪进度");
        return ApiResponse.ok("部署已触发", data);
    }

    /**
     * 触发 prod 晋升（仅 staging SUCCESS 后才能点）
     */
    @PostMapping("/deploy-prod")
    public ApiResponse<Map<String, Object>> deployProd() {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());

        // 铁律：必须先有 staging SUCCESS 才能晋升 prod
        ReleaseHistory stagingLatest = releaseService.getCurrentVersion(ReleaseHistory.ReleaseEnv.STAGING);
        if (stagingLatest == null) {
            return ApiResponse.fail(409, "尚无 staging 成功部署记录，请先部署 staging");
        }

        // 防并发
        if (releaseService.hasRunningDeployment()) {
            return ApiResponse.fail(409, "已有部署正在执行，请等待完成后再试");
        }

        CurrentUser cu = JwtAuthFilter.getCurrentUser();
        Long historyId = releaseService.initProdPromote(cu.getUserId());
        releaseService.executeProdPromote(historyId);  // 异步，不阻塞

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("historyId", historyId);
        data.put("stagingVersion", stagingLatest.getVersion());
        data.put("message", "prod 晋升已启动，请通过 /api/admin/release/history 跟踪进度");
        return ApiResponse.ok("晋升已触发", data);
    }

    // ──────────── 历史查询 ────────────

    /**
     * 历史列表（分页）
     */
    @GetMapping("/history")
    public ApiResponse<Page<ReleaseHistory>> getHistory(
            @RequestParam(required = false) ReleaseHistory.ReleaseEnv env,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());

        Pageable pageable = PageableValidator.safeOf(page, size);
        Page<ReleaseHistory> result = env == null
                ? historyRepo.findAllByOrderByStartedAtDesc(pageable)
                : historyRepo.findByEnvOrderByStartedAtDesc(env, pageable);

        return ApiResponse.ok(result);
    }

    /**
     * 单条详情（含完整 log）
     */
    @GetMapping("/{id}")
    public ApiResponse<ReleaseHistory> getById(@PathVariable Long id) {
        ApiResponse<?> deny = requirePlatformAdmin();
        if (deny != null) return ApiResponse.fail(deny.getCode(), deny.getMessage());

        ReleaseHistory hist = historyRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("发布记录不存在: " + id));
        return ApiResponse.ok(hist);
    }
}