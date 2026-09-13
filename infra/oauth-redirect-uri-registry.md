# 凌瑶智数 · OAuth redirect_uri 厂商注册清单

> **owner**: 主站窗口 · **reviewer**: 主人 · **创建**: 2026-09-13
> **最高风险**: 改 redirect_uri 必须走厂商审核（飞书 0.5~3 天，企微 1~3 天）
> **关联文档**: `infra/port-change-checklist.md` §3.3

---

## §1 现状盘点（2026-09-13 · 主站窗口自查）

### 已注册的 OAuth 提供商
按主站 backend `CompanySsoConfig` 实体枚举（V2.0.77.6）：
- **FEISHU** = 飞书
- **DINGTALK** = 钉钉
- **WECOM** = 企业微信
- **GENERIC_OIDC** = OIDC 通用
- **SAML** = SAML

按 D-6 落地状态：`SsoEnabledProvidersController` 已返回前端可见 provider 列表，5 个全支持。

### ⚠️ 待主人提供真实凭据
按 MEMORY 铁律：
- 飞书 APP_ID / APP_SECRET 已在 .env 真实配置（生产）
- 钉钉 / 企微 凭据 **未提供**（D-3 拍板用空壳实现，主人拍板 ③）
- GENERIC_OIDC / SAML 未启用真实 IdP

**因此：本文件当前**大量条目为占位符**，主人提供真实凭据后必须补全**。

---

## §2 主站 redirect_uri 注册清单（每个环境 × 每个厂商）

### 2.1 dev 环境（127.0.0.1）
| 厂商 | redirect_uri | 注册状态 | 审核状态 | 注册时间 | 失效条件 |
|---|---|---|---|---|---|
| 飞书 | `http://127.0.0.1:9091/api/auth/sso/feishu/callback` | ⏳ 待主人提供 APP_ID | ⏳ | — | — |
| 钉钉 | `http://127.0.0.1:9091/api/auth/sso/dingtalk/callback` | ⏳ 待主人提供凭据 | ⏳ | — | — |
| 企微 | `http://127.0.0.1:9091/api/auth/sso/wecom/callback` | ⏳ 待主人提供凭据 | ⏳ | — | — |
| OIDC | `http://127.0.0.1:9091/api/auth/sso/oidc/callback` | ⏳ 未启用 | — | — | — |
| SAML | `http://127.0.0.1:9091/api/auth/sso/saml/acs` | ⏳ 未启用 | — | — | — |

### 2.2 staging 环境（CVM 9092）
| 厂商 | redirect_uri | 注册状态 | 审核状态 | 注册时间 | 失效条件 |
|---|---|---|---|---|---|
| 飞书 | `http://staging.lingyao-digital.com:9092/api/auth/sso/feishu/callback` | ⏳ 待主人提供 | ⏳ | — | — |
| 钉钉 | `http://staging.lingyao-digital.com:9092/api/auth/sso/dingtalk/callback` | ⏳ 待主人提供 | ⏳ | — | — |
| 企微 | `http://staging.lingyao-digital.com:9092/api/auth/sso/wecom/callback` | ⏳ 待主人提供 | ⏳ | — | — |

### 2.3 prod 环境（CVM 9091 / 私有化部署）
| 厂商 | redirect_uri | 注册状态 | 审核状态 | 注册时间 | 失效条件 |
|---|---|---|---|---|---|
| 飞书 | `https://lingyao-digital.com/api/auth/sso/feishu/callback` | ⏳ 待主人提供 | ⏳ | — | — |
| 钉钉 | `https://lingyao-digital.com/api/auth/sso/dingtalk/callback` | ⏳ 待主人提供 | ⏳ | — | — |
| 企微 | `https://lingyao-digital.com/api/auth/sso/wecom/callback` | ⏳ 待主人提供 | ⏳ | — | — |

**注**：prod 用 Nginx 反代（443 → 9091），redirect_uri 走标准 443，不带端口。

---

## §3 子平台 SSO 回调 URI（GEOM/MPDM/AIDD）

每个子平台都有自己的 SSO 接收端（接收主站 JWT claims）。

### 3.1 GEOM（8090）
| 环境 | callback URI | 备注 |
|---|---|---|
| dev | `http://127.0.0.1:5180/sso-callback` (前端) → `http://127.0.0.1:8090/sso/callback` (后端) | D-10 已加 demo redirect |
| staging | `http://staging.lingyao-digital.com:5181/sso-callback` → `:8091/sso/callback` | — |
| prod | `https://lingyao-digital.com/geom/sso-callback` → GEOM 内部 8090 | 反代层加 /geom 前缀 |

### 3.2 MPDM（8100）
| 环境 | callback URI | 备注 |
|---|---|---|
| dev | `http://127.0.0.1:3100/sso-callback` → `:8100/sso/callback` | — |
| staging | TBD | — |
| prod | `https://lingyao-digital.com/mpdm/sso-callback` | — |

### 3.3 AIDD / POR-M / D-Info / MED-PPT
（同模式，按实际部署时补全）

---

## §4 改 URI 流程（高风险 · 必走）

### Step 1 · 提交厂商审核
- [ ] 登录厂商后台（飞书 open.feishu.cn / 钉钉 open-dev.dingtalk.com / 企微 work.weixin.qq.com）
- [ ] 进入应用管理 → 安全设置 → OAuth 2.0 / 授权回调
- [ ] 添加新 URI（不要立刻删旧的）
- [ ] 提交审核

### Step 2 · 等待审核
- **飞书**: 0.5~3 个工作日（自动审核快，人工审核慢）
- **钉钉**: 立即生效（无需审核）
- **企微**: 1~3 个工作日（需企业微信认证）
- **OIDC 通用**: 取决于 IdP（Keycloak/Auth0/Okta 通常立即）

### Step 3 · 审核通过后再切 DNS / Nginx
- [ ] 审核通过后，再修改 Nginx 反代 / DNS 切流
- [ ] 旧 URI 保留 7 天（防止用户旧书签/扫码失败）

### Step 4 · 删除旧 URI
- [ ] 7 天后无异常，删除厂商后台旧 URI
- [ ] 厂商后台保留截图存档（合规）

---

## §5 踩坑教训（P0 · 历史 CHANGELOG）

### 教训 1: 飞书 redirect_uri 改动需要审核，没等审核完就切 DNS
**结果**: 用户 3 天无法登录飞书扫码，营业额损失
**对策**: 永远**先提交厂商审核 → 等通过 → 再切 DNS**

### 教训 2: OIDC IdP（Keycloak）redirect_uri 不匹配 → 用户 401
**结果**: 接入 Keycloak 失败
**对策**: OIDC 的 redirect_uri 必须**精确匹配**（含 trailing slash / query 参数）

### 教训 3: SAML ACS URL 写错 → 用户绑定失败
**结果**: SAML 用户无法与主站账号关联
**对策**: SAML 的 ACS URL 必须**与 IdP metadata 一致**（通常含域名）

---

## §6 应急联系（待补）

| 厂商 | 紧急联系电话 | 工单系统 |
|---|---|---|
| 飞书 | 待补 | https://open.feishu.cn/document/ |
| 钉钉 | 待补 | https://open-dev.dingtalk.com/ |
| 企微 | 待补 | https://work.weixin.qq.com/ |

---

## §7 验证命令

```bash
# 检查主站 SSO callback 端点
curl -X POST http://127.0.0.1:9091/api/auth/sso/feishu/callback
# 应返回 400/401（参数缺失），不是 404

# 检查 GEOM SSO callback
curl http://127.0.0.1:8090/sso/callback
# 应返回 200 + 引导页（带 token 参数时）
```

---

*maintained by: 主站窗口 · status: 占位符待主人提供真实凭据 · last reviewed: 2026-09-13*
