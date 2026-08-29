# 凌瑶智数 · SSO + CORS 策略文档

> **适用场景**：凌瑶主站 + 5 个子产品（GEOM/HPD/AIDD/Dinfo/PORM）的 SSO 跳转 + 跨域 cookie 共享策略
>
> **文档版本**：V2.0.11（2026-08-29）· 配套 `01-main-repo-stability-fixes.md` + `02-sub-product-sso-adaptation.md` 使用
>
> **目标读者**：跨项目开发（5 个子产品仓各自团队）、CVM 部署方、架构审查者

---

## 一、协议分层

凌瑶 SSO 涉及 3 层协议，需清晰区分：

| 层 | 内容 | 主站职责 | 子产品职责 |
|---|---|---|---|
| **L1 鉴权** | JWT 签发 + 验签 | 用 `LINGYAO_JWT_SECRET` 签发 | 用**同一个 secret** 验签 |
| **L2 跳转** | URL 参数传递 + 5 标准字段 | `window.location.href` 跳子产品 URL | URL 解析 → 调 `/api/sso/login` |
| **L3 Cookie** | 跨域 session 共享 | 主站写 `lingyao_session`（domain=.lingyao.cn）| 子产品读同一 cookie |

> ❗ **常见误区**：把 L1（JWT 验签）和 L3（Cookie 共享）混在一起 → "共享 cookie 不安全"。实际上 L1 验签是后端独立校验（不依赖 cookie），L3 cookie 只是 session 优化（可关）。

---

## 二、L1 鉴权（JWT 签发/验签）

### 2.1 主站签发

```java
// SubTaskController.enter() 中提取的 JWT
String token = extractBearerToken(request);  // 从 Authorization: Bearer xxx 读
```

主站用 `application.yml` 的 `lingyao.jwt.secret` 签发 HS512 token：

```yaml
lingyao:
  jwt:
    secret: ${LINGYAO_JWT_SECRET:lingyao-platform-default-secret-key-change-in-production-must-be-at-least-256-bits-long}
    token-ttl-seconds: 7200  # 2 小时
```

### 2.2 子产品验签

子产品用**同一个 secret** + `audience='lingyao-sso'` 验签：

```python
# FastAPI 通用模板
import jwt, os

JWT_SECRET = os.environ["LINGYAO_JWT_SECRET"]  # 必须与主站一致

def verify_platform_token(token: str) -> dict:
    """验签主站 JWT，返回 claims"""
    return jwt.decode(
        token,
        JWT_SECRET,
        algorithms=["HS512"],
        audience="lingyao-sso",
    )
```

### 2.3 关键约定

- **算法统一**：HS512（主站默认）
- **Audience 统一**：`lingyao-sso`（子产品必须校验）
- **Secret 共享**：`LINGYAO_JWT_SECRET` 环境变量注入（密钥文件**绝对不进 git**）
- **TTL**：7200 秒（2 小时），子产品可基于 user_id 缓存本地 user 信息

---

## 三、L2 跳转（5 标准参数）

### 3.1 URL 协议

```
{base_url}/
  ?tenant_id={company_id}            # 租户 ID
  &user_id={user_id}                 # 主站 user_id
  &user={username}                   # URL encoded
  &display_name={display_name}       # URL encoded
  &platform_token={JWT}             # URL encoded
```

### 3.2 主站职责

`SubTaskController.enter()` 已实现：
- ✅ 5 参数拼接（`tenant_id/user_id/user/display_name/platform_token`）
- ✅ URL encoder（UTF-8）
- ✅ 权限校验（`existsByUserIdAndProductIdAndStatus(ACTIVE)`）
- ✅ 失败返回 `NO_ACCESS` + 主站弹模弹（`showAccessDeniedModal`）

### 3.3 子产品职责

子产品前端入口（React 模板）：

```typescript
// main.tsx
const params = new URLSearchParams(window.location.search);
const platformToken = params.get("platform_token");
if (platformToken) {
  // 调后端验签 + 写本地 session
  window.location.href = `/api/sso/login?token=${platformToken}&redirect=/dashboard`;
}
```

子产品前端入口（Vue 模板）：

```typescript
// router/index.ts
router.beforeEach((to, from, next) => {
  const token = new URLSearchParams(window.location.search).get("platform_token");
  if (token && to.path === "/sso/callback") {
    window.location.href = `/api/sso/login?token=${token}&redirect=/dashboard`;
  } else {
    next();
  }
});
```

---

## 四、L3 Cookie 策略（核心文档）

### 4.1 生产环境：同 `.lingyao.cn` 子域共享

> **推荐方案**。所有主子产品都在 `*.lingyao.cn` 子域下，cookie 设置 `domain=.lingyao.cn` 实现主子站 session 共享。

```
主站:    portal.lingyao.cn         (凌瑶登录入口)
GEO:     geo.lingyao.cn            (GEOM 智策)
HPD:     hpd.lingyao.cn            (皓元)
AIDD:    aidd.lingyao.cn           (AIDD)
Dinfo:   dinfo.lingyao.cn          (辰录)
PORM:    porm.lingyao.cn           (明枢)
```

**主站 set cookie**：

```python
# 后端
response.set_cookie(
    key="lingyao_session",
    value=session_token,
    domain=".lingyao.cn",       # ← 关键：所有子域共享
    httponly=True,
    secure=True,                # 仅 HTTPS
    samesite="lax",             # 或 "strict"
    max_age=7200,               # 与 JWT TTL 一致
)
```

**子产品读 cookie**：

```python
session_token = request.cookies.get("lingyao_session")
if not session_token:
    # 没有 cookie → 跳主站重新登录
    return RedirectResponse("https://portal.lingyao.cn/login.html")
```

### 4.2 开发环境：localhost 无 domain 共享

> 开发机所有服务都在 `127.0.0.1` / `localhost`，**不能用 `domain=.localhost`**（浏览器会拒绝）。

**方案 A**：每个子产品写自己的 session cookie（key 不同）：

| 子产品 | Cookie name | 域 |
|---|---|---|
| 主站 | `lingyao_session` | `127.0.0.1` |
| GEO | `geo_session` | `127.0.0.1` |
| HPD | `hpd_session` | `127.0.0.1` |
| AIDD | `aidd_session` | `127.0.0.1` |
| Dinfo | `dinfo_session` | `127.0.0.1` |
| PORM | `porm_session` | `127.0.0.1` |

**方案 B**：用 `SameSite=Lax`（推荐）：

```python
response.set_cookie(
    key="lingyao_session",
    value=session_token,
    httponly=True,
    samesite="lax",   # 允许顶级导航跳转
    secure=False,     # dev 不强制 HTTPS
    max_age=7200,
)
```

主站跳转是 `window.location.href` 顶级导航，`SameSite=Lax` 允许 cookie 自动带上。

### 4.3 跨域场景：完全独立域

> 如果主子产品在不同域（如 `lydmed.com` + `geo-cn.com`），**不能共享 cookie**。

**方案**：JWT-only（不依赖 cookie 共享）：

```python
# 子产品 /api/sso/login 端点
@app.get("/api/sso/login")
async def sso_login(token: str, redirect: str = "/"):
    payload = verify_platform_token(token)
    # 不写 cookie，仅用 token 验签
    user = get_or_create_user(payload["user_id"], payload["user"])
    # 用 URL 参数带 session 回去（或重定向时把 session 写子产品自己的 domain cookie）
    response = RedirectResponse(url=redirect)
    response.set_cookie(
        key="lingyao_session",
        value=token,  # 直接存 token
        httponly=True,
        samesite="strict",
        max_age=7200,
    )
    return response
```

---

## 五、CORS 配置

### 5.1 子产品 FastAPI CORS 白名单

> 主站 `window.location.href` 跳转**不是** fetch 请求，**没有 CORS 问题**。
>
> 但子产品**前端**如果调主站 API（如 `https://portal.lingyao.cn/api/users/me`），需要 CORS 白名单。

```python
# 子产品 backend/main.py
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "https://portal.lingyao.cn",      # 主站
        "https://hpd.lingyao.cn",         # 兄弟子产品（可选）
        "http://localhost:8765",          # 开发机主站
        "http://localhost:3100",          # 开发机 HPD
    ],
    allow_credentials=True,               # 允许 cookie 携带
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)
```

### 5.2 Nginx 反代层 CORS

如果主子产品都通过 Nginx 反代：

```nginx
server {
    listen 443 ssl http2;
    server_name geo.lingyao.cn;

    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # CORS（如果前端跨域调 API）
        add_header Access-Control-Allow-Origin "https://portal.lingyao.cn" always;
        add_header Access-Control-Allow-Credentials true always;
        add_header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS" always;
        add_header Access-Control-Allow-Headers "Authorization, Content-Type" always;
    }
}
```

---

## 六、安全要点

### 6.1 ⚠️ 绝对不能做的事

1. **JWT secret 写死在代码里** → 必须用 `LINGYAO_JWT_SECRET` 环境变量
2. **JWT secret 提交到 git** → 用 `.gitignore` 排除 secret 文件 + 用 GitGuardian 检测
3. **Cookie 不设 `HttpOnly`** → 前端 JS 可读 → XSS 可窃取 session
4. **Cookie 不设 `Secure`** → HTTP 明文传输 → 中间人攻击
5. **JWT TTL 设太长** → 推荐 7200s（2 小时），超过需 refresh token
6. **JWT audience 不校验** → 任意 token 都能验签 → 必须验 `aud='lingyao-sso'`
7. **共享 cookie 设 `SameSite=None`** → 跨站请求都带 → CSRF 风险

### 6.2 ✅ 推荐的安全实践

1. **JWT TTL 2 小时** + refresh token 7 天（v2.1+ 引入）
2. **Cookie `HttpOnly=True` + `Secure=True` + `SameSite=Lax`**
3. **HTTPS only**（生产环境强制）
4. **子产品 audit_log 记录每次 SSO 来源**（`source='lingyao'`）
5. **Secret 注入用 vault / kubernetes secrets / 部署时 stdin**
6. **定期轮换 secret**（建议每 90 天）
7. **异常登录告警**（同一 user_id 5 分钟内跨 3 个 IP → 邮件告警）

---

## 七、SSO 失败兜底（每个子产品必做）

子产品**必须保留**本地登录入口（不走 SSO），用于：

- 主站不可用时
- Secret 轮换期间
- 开发者本地调试
- 子产品独立运营（脱离子产品账户体系）

```typescript
// React 模板
<LoginPage>
  <Tabs>
    <TabPane tab="凌瑶账号登录" key="sso">
      {/* 显示"主站登录"按钮，跳 portal.lingyao.cn */}
      <Button onClick={() => window.location.href = "https://portal.lingyao.cn/login.html?return=" + encodeURIComponent(window.location.href)}>
        凌瑶账号登录
      </Button>
    </TabPane>
    <TabPane tab="本地账号登录" key="local">
      <LocalLoginForm />
    </TabPane>
  </Tabs>
</LoginPage>
```

---

## 八、审计日志规范

每个子产品 `audit_log` 表记录 SSO 登录：

| 字段 | 类型 | 示例 |
|---|---|---|
| `id` | BIGINT PK | 1001 |
| `user_id` | BIGINT | 1 |
| `source` | VARCHAR(32) | `lingyao` / `local` / `feishu`（GEOM 保留） |
| `ip` | VARCHAR(64) | `192.168.1.100` |
| `user_agent` | VARCHAR(512) | `Mozilla/5.0 ...` |
| `created_at` | TIMESTAMP | `2026-08-29 18:30:00` |
| `success` | BOOLEAN | `true` / `false` |
| `error_msg` | VARCHAR(255) | `JWT signature expired` |

---

## 九、跨项目治理 checklist

主子产品上线前，**每个**子产品团队必须确认：

- [ ] 子产品 FastAPI `/api/sso/login` 端点已实现
- [ ] 子产品前端 URL 参数解析已实现（React/Vue 模板见 §3.3）
- [ ] `LINGYAO_JWT_SECRET` 环境变量已配置（与主站一致）
- [ ] 子产品 CORS 白名单已加主站域名
- [ ] Cookie 策略已确认（生产用 `.lingyao.cn`，dev 用 `SameSite=Lax`）
- [ ] 审计日志已记录 SSO 来源
- [ ] 本地登录入口保留（兜底）
- [ ] 异常登录告警已对接
- [ ] Secret 轮换 SOP 已文档化

---

## 十、参考文档

- **主仓稳定性方案**：`01-main-repo-stability-fixes.md`
- **子产品 SSO 改造方案**：`02-sub-product-sso-adaptation.md`
- **主仓 CVM 部署**：`deploy/cvm/DEPLOYMENT.md`
- **CHANGELOG**：`CHANGELOG.md`

---

_文档版本 V2.0.11 · 2026-08-29 更新 · 配套主仓 V2.0.11 使用_