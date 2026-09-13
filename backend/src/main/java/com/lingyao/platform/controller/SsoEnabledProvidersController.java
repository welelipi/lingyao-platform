package com.lingyao.platform.controller;

import com.lingyao.platform.dto.ApiResponse;
import com.lingyao.platform.entity.CompanySsoConfig;
import com.lingyao.platform.repository.CompanyRepository;
import com.lingyao.platform.repository.CompanySsoConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 公开端点：返回某公司当前已启用的 SSO Provider 列表
 *
 * 用途：GEOM 登录页（V0.10.26+）调用此端点判断主站 SSO 方案，
 *       决定 UsersOps.vue 是按飞书/钉钉/企微/OIDC 哪条链路要求 Union ID 选填。
 *
 * 契约（V2.0.77.21）：
 *   GET /api/sso/enabled-providers?company_code=XXX
 *   → 200 { code:0, data:{ providers: [ {provider:"FEISHU", label:"飞书"}, ... ] } }
 *   → 200 { code:0, data:{ providers: [] } }  // 找不到公司 / 未配 SSO
 *
 * V2.0.77.21 新增（2026-09-12 · D-6 r5 收尾）—— 偿还 09-08 主人「主站补 enabled-providers」欠账。
 * 不鉴权（公开端点），只透 enabled=true 的 provider 枚举 + 中文 label，
 * 绝不出 app_id/app_secret 任何凭据。
 */
@RestController
@RequestMapping("/api/sso")
public class SsoEnabledProvidersController {

    @Autowired private CompanyRepository companyRepo;
    @Autowired private CompanySsoConfigRepository configRepo;

    private static final Map<CompanySsoConfig.Provider, String> LABELS = new LinkedHashMap<>();
    static {
        // 与 GEOM 端 _PROVIDER_LABELS_V2（lingyao_sso.py L469-475）保持一致
        LABELS.put(CompanySsoConfig.Provider.FEISHU,       "飞书");
        LABELS.put(CompanySsoConfig.Provider.DINGTALK,     "钉钉");
        LABELS.put(CompanySsoConfig.Provider.WECOM,        "企业微信");
        LABELS.put(CompanySsoConfig.Provider.GENERIC_OIDC, "OIDC");
        LABELS.put(CompanySsoConfig.Provider.SAML,         "SAML");
    }

    @GetMapping("/enabled-providers")
    public ApiResponse<Map<String, Object>> enabledProviders(@RequestParam("company_code") String companyCode) {
        // 1) 校验 company_code
        if (companyCode == null || companyCode.isBlank()) {
            return ApiResponse.fail(400, "company_code 必填");
        }
        var companyOpt = companyRepo.findByCode(companyCode);
        if (companyOpt.isEmpty()) {
            return ApiResponse.ok(Map.of("providers", List.of()));  // 公司不存在 → 空列表（前端降级）
        }
        Long companyId = companyOpt.get().getId();

        // 2) 查该 company 启用的 provider 列表
        List<CompanySsoConfig> enabled = configRepo.findByCompanyIdAndEnabledTrue(companyId);
        List<Map<String, String>> providers = new ArrayList<>();
        for (CompanySsoConfig c : enabled) {
            String label = LABELS.getOrDefault(c.getProvider(), c.getProvider().name());
            providers.add(Map.of(
                "provider", c.getProvider().name(),
                "label", label
            ));
        }
        return ApiResponse.ok(Map.of("providers", providers));
    }
}
