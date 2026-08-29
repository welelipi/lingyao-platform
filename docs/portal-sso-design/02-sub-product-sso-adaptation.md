# 子产品 SSO 接收端改造方案（4 子产品）

> **状态**：起草版（未实施）· 主人决策后才开始落地
> **写于**：2026-08-29 17:55
> **作者**：架构师 Agent
> **作用**：让 4 个子产品（HPD/AIDD/Dinfo/PORM）能识别主网站 `/api/sub/{code}/enter` 发来的 `platform_token`，自动登录并跳到首页

---

## 0. 背景

主网站侧 SSO 跳转协议（已落地，凌瑶 v2.0.10）：

```
URL:    {base_url}/#/sso/callback
Params: ?tenant_id={company_id}
       &user_id={user_id}
       &user={username}
       &display_name={display_name}
       &platform_token={Bearer JWT}
```

主网站发送的就是这 5 个参数，子产品只需要：
1. 解析 URL 参数
2. 把 `platform_token` 发给自家后端验签
3. 后端验签通过 → 创建本地用户 + 写 session cookie
4. 重定向到首页

---

## 1. 通用模板（4 子产品共用）

### 1.1 后端 FastAPI 通用 SSO 端点

```python
# backend/app/api/sso.py （新增文件）
"""
凌瑶主站 SSO 接收端（统一模板）

部署要求：
- 环境变量 LINGYAO_JWT_SECRET 与主仓 application.yml 的 lingyao.jwt.secret 一致
- 算法 HS256（与主仓 JwtService 一致）
- audience=lingyao-sso（防 token 重放到其他用途）
"""
import os
import jwt
from fastapi import APIRouter, Response, HTTPException
from fastapi.responses import RedirectResponse

router = APIRouter()

JWT_SECRET = os.environ.get("LINGYAO_JWT_SECRET")
JWT_ALGORITHM = "HS256"
JWT_AUDIENCE = "lingyao-sso"


@router.get("/api/sso/login")
async def sso_login(
    token: str,
    redirect: str = "/",
    response: Response = None,
):
    """
    凌瑶主站 SSO 入口
    接收 platform_token，验签后创建/查找本地用户，写本地 session
    """
    if not JWT_SECRET:
        raise HTTPException(500, "LINGYAO_JWT_SECRET 未配置")

    try:
        # 1. 验签（共享 secret + audience 校验）
        payload = jwt.decode(
            token,
            JWT_SECRET,
            algorithms=[JWT_ALGORITHM],
            audience=JWT_AUDIENCE,
        )
    except jwt.ExpiredSignatureError:
        raise HTTPException(401, "platform_token 已过期，请重新登录主站")
    except jwt.InvalidAudienceError:
        raise HTTPException(401, "platform_token audience 不匹配")
    except jwt.InvalidTokenError as e:
        raise HTTPException(401, f"platform_token 无效: {e}")

    # 2. 提取主站用户信息
    user_id = payload.get("user_id") or payload.get("uid")
    username = payload.get("user") or payload.get("username") or payload.get("sub")
    display_name = payload.get("display_name") or username
    tenant_id = payload.get("tenant_id") or payload.get("cid") or payload.get("company_id")
    role = payload.get("role") or payload.get("rcode") or "user"

    if not user_id or not username:
        raise HTTPException(400, "platform_token 缺少 user_id 或 username")

    # 3. 查找/创建本地用户（由各子产品实现 get_or_create_user）
    user = get_or_create_user(
        user_id=user_id,
        username=username,
        display_name=display_name,
        tenant_id=tenant_id,
        role=role,
    )

    # 4. 写本地 session（由各子产品实现 create_local_session）
    session_token = create_local_session(user)

    # 5. 重定向到首页
    resp = RedirectResponse(url=redirect, status_code=302)
    resp.set_cookie(
        key=LOCAL_SESSION_COOKIE_KEY,  # 各子产品用自己的 key
        value=session_token,
        httponly=True,
        # secure=True,  # 生产 https 才开
        samesite="lax",
        max_age=86400,
    )
    return resp


# === 各子产品实现的辅助函数（占位）===

def get_or_create_user(user_id, username, display_name, tenant_id, role):
    """各子产品实现：根据 user_id 查找或创建本地 user"""
    raise NotImplementedError


def create_local_session(user):
    """各子产品实现：生成本地 session token"""
    raise NotImplementedError


LOCAL_SESSION_COOKIE_KEY = "lingyao_sso_session"  # 各子产品可改名
```

### 1.2 前端通用入口（React）

```typescript
// frontend/src/main.tsx（每个子产品的入口文件，加一段 useEffect）
import { useEffect } from 'react';

function SsoBootstrap({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const platformToken = params.get('platform_token');

    if (platformToken) {
      // === 凌瑶 SSO 接收（V2.0.10+）===
      // 清掉 URL 参数，避免泄漏到 history
      const redirect = params.get('redirect') || '/';
      window.location.href = `/api/sso/login?token=${encodeURIComponent(platformToken)}&redirect=${encodeURIComponent(redirect)}`;
      return;
    }

    // 非 SSO 入口：正常加载
  }, []);

  return <>{children}</>;
}

// 在 ReactDOM.render 之前包一层
```

### 1.3 前端通用入口（Vue）

```typescript
// frontend/src/router/index.ts（beforeEach 守卫）
router.beforeEach((to, from, next) => {
  // === 凌瑶 SSO 接收（V2.0.10+）===
  const params = new URLSearchParams(window.location.search);
  const platformToken = params.get('platform_token');

  if (platformToken) {
    const redirect = params.get('redirect') || '/';
    // 后端验签并写 cookie
    window.location.href = `/api/sso/login?token=${encodeURIComponent(platformToken)}&redirect=${encodeURIComponent(redirect)}`;
    return;
  }

  next();
});
```

---

## 2. 4 个子产品差异化实施

### 2.1 HPD 皓元（MPD-myself 仓）

#### 现状
- **仓**：`/Users/hua/Documents/myself/MPD-myself/`
- **前端**：React + Vite + React Router 6（`frontend/src/App.tsx`，端口 3100）
- **后端**：FastAPI（`backend/app/main.py`，端口 8100）
- **数据库**：SQLite（`hospital.db` / `mpd.db`）
- **现有登录**：本地用户名/密码 + 飞书（?）

#### 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `backend/app/api/sso.py` | 上面的通用模板 |
| `backend/app/main.py` | `app.include_router(sso_router)` |
| `backend/app/api/users.py` | 实现 `get_or_create_user`：按 `user_id` 查 user 表，不存在则创建 |
| `backend/app/security.py` | 实现 `create_local_session`：复用现有 session 生成逻辑 |
| `frontend/src/App.tsx` | 加 `/sso/callback` 路由 + `SsoBootstrap` 组件 |
| **新增** `frontend/src/pages/SsoCallback.tsx` | 调 `/api/sso/login?token=...` 然后 router.push('/') |

#### 验证
```bash
# 主网站登录后点 HPD 卡片 → 应跳到 MPD-myself 3100 自动登录
# 期望：浏览器看到 http://localhost:3100/ 而不是 /login
# dev 环境验证：
curl -H "Cookie: lingyao_token=$TOKEN" \
  http://localhost:9091/api/sub/hpd/enter | jq -r .data.redirectUrl
# 应返回：http://localhost:3100/#/sso/callback?platform_token=...
```

#### 工作量
0.5 人日

---

### 2.2 AIDD Copilot（AIDD 仓）

#### 现状
- **仓**：`/Users/hua/Documents/myself/AIDD/ai-project-copilot/`
- **前端**：React + Vite（端口 13000）
- **后端**：FastAPI（端口 18080）
- **数据库**：SQLite（`data/aidd.db`）
- **现有登录**：本地用户名/密码（?）

⚠️ **AIDD frontend 结构需进一步勘察**（仓里只有 `app/components/hooks/lib/types/`，入口文件待定位）。建议 Phase 1 实施前先做 1 小时勘察。

#### 改动清单（预估）

| 文件 | 改动 |
|---|---|
| **新增** `backend/app/api/sso.py` | 同通用模板 |
| `backend/app/main.py` | 注册 router |
| `backend/app/services/user_service.py` | 实现 `get_or_create_user`（按 user_id 关联）|
| `frontend/src/main.tsx` 或 `App.tsx` | 加 SsoBootstrap 组件 |
| **新增** `frontend/src/pages/SsoCallback.tsx` | 调 SSO 端点后 router.push('/') |

#### 工作量
0.5 人日（含 0.1 日勘察）

---

### 2.3 Dinfo 辰录（Dinfo 仓）

#### 现状
- **仓**：`/Users/hua/Documents/myself/Dinfo/`
- **前端**：Vue 3 + Vite + Vue Router（端口 5181）
- **后端**：FastAPI（端口 8281）
- **数据库**：SQLite（`backend/data/dept_fill.db`）
- **现有登录**：飞书 OAuth（已有 `/auth/callback` + `Callback.vue`）

#### 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `backend/app/api/sso.py` | 同通用模板 |
| `backend/app/main.py` | 注册 router |
| `backend/app/routers/auth.py` | 实现 `get_or_create_user`（复用飞书登录的用户表）|
| `frontend/src/router/index.ts` | 加 `/sso/callback` 路由 + beforeEach 守卫（保留 `/auth/callback` 不动）|
| **新增** `frontend/src/views/SsoCallback.vue` | 调 SSO 端点后 router.push('/') |

#### 注意
- 现有 `/auth/callback` 是飞书 OAuth 流程，**不要破坏**
- 新增 `/sso/callback` 是凌瑶主站 SSO，**两条路径并存**
- `localStorage.getItem('user')` 中已有飞书登录用户，需兼容：SSO 用户优先于飞书用户

#### 工作量
0.3 人日

---

### 2.4 GEOM 棱镜（凌瑶/geom 仓）

#### 现状
- **仓**：`/Users/hua/Documents/myself/凌瑶/geom/`（独立 git 仓副本）
- **前端**：Vue 3 + Vite + Vue Router Hash 模式（端口 5180）
- **后端**：FastAPI（端口 8090）
- **数据库**：SQLite（`geo.db` / `prism.db`）
- **现有登录**：飞书 OAuth（已有 `/feishu/callback` + `FeishuCallback.vue`）

⚠️ **铁律**：GEOM 源头 `code/geo-platform/` 不可动！本改造只改 `凌瑶/geom/`（副本），不改源头。

#### 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `geo-backend-py/app/api/sso.py` | 同通用模板 |
| `geo-backend-py/app/main.py` | 注册 router |
| `geo-backend-py/app/services/auth_service.py` | 实现 `get_or_create_user` |
| `geo-frontend/src/router/index.js` | 加 `/sso/callback` 路由 + beforeEach 守卫（保留 `/feishu/callback` 不动）|
| **新增** `geo-frontend/src/pages/SsoCallback.vue` | 调 SSO 端点后 router.push('/action-center') |

#### 注意
- 现有 `/feishu/callback` + `FeishuCallback.vue` 是飞书 OAuth 流程，**不要破坏**
- 新增 `/sso/callback` 是凌瑶主站 SSO
- Vue Router 用的是 `createWebHashHistory`，所以 URL 是 `#/sso/callback`（带 hash）

#### 工作量
0.3 人日

---

### 2.5 PORM 明枢（MPD-myself 仓，HPD 同仓）

#### 现状
- **仓**：`/Users/hua/Documents/myself/MPD-myself/`（与 HPD 共享）
- **前端**：React + Vite + React Router 6（`frontend/src/App.tsx`，端口 3190）
- **后端**：FastAPI（`backend/app/main.py`，端口 8280，与 HPD 8100 同仓不同端口）
- **数据库**：SQLite（`hospital_hoyuan.db` / `hospital.db`）
- **现有登录**：本地用户名/密码 + 飞书（已有 `/feishu/callback` + `FeishuCallback.tsx`）

#### 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `backend/app/api/sso.py` | 同通用模板 |
| `backend/app/main.py` | 注册 router |
| `backend/app/services/user_service.py` | 实现 `get_or_create_user` |
| `frontend/src/App.tsx` | 加 `/sso/callback` 路由 + 组件 |
| **新增** `frontend/src/pages/hoyuan/SsoCallback.tsx` | 调 SSO 端点后 router.push('/') |

#### 注意
- 与 HPD 共享后端代码，但 8280 端口独立运行
- 现有 `/feishu/callback` + `FeishuCallback.tsx` 是飞书 OAuth 流程，**不要破坏**
- 主控台跳 PORM 时 URL 是 `http://localhost:3190/#/sso/callback?platform_token=...`

#### 工作量
0.5 人日（与 HPD 可并行）

---

## 3. 共享 JWT Secret 部署

### 3.1 主仓侧

`backend/src/main/resources/application.yml`：
```yaml
lingyao:
  jwt:
    secret: ${LINGYAO_JWT_SECRET:lingyao-platform-default-secret-key-change-in-production-must-be-at-least-256-bits-long}
```

启动时：
```bash
export LINGYAO_JWT_SECRET=$(openssl rand -base64 48)
java -jar lingyao-platform.jar
```

### 3.2 子产品侧

每个子产品的环境变量文件（**不入 git**，参考主仓 `.env.private`）：
```bash
# backend/env.prod（每个子产品一份）
LINGYAO_JWT_SECRET=<与主仓完全一致>
```

### 3.3 Secret 传递路径

主控台生成的 platform_token payload 示例：
```json
{
  "sub": "admin",
  "user_id": "1",
  "tenant_id": "1",
  "role": "PLATFORM_ADMIN",
  "display_name": "管理员",
  "aud": "lingyao-sso",
  "exp": 1725023456,
  "iat": 1724937056
}
```

子产品用相同的 `LINGYAO_JWT_SECRET` + `audience=lingyao-sso` 验签。

---

## 4. CORS 配置（主子产品互通）

每个子产品的 `backend/app/main.py`：
```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8765",          # 主站 dev
        "http://127.0.0.1:8765",
        "http://localhost:9091",          # 主站 API
        "https://lingyao.cn",             # 主站 prod
        "http://localhost:3100",          # 子产品自己 dev（按需）
        # ... 按需添加其他子产品域名
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

---

## 5. 验证 SOP（端到端）

### 5.1 dev 环境

```bash
# 1. 主仓启动
cd /Users/hua/Documents/myself/凌瑶
export LINGYAO_JWT_SECRET="test-secret-32-chars-long-aaaa"
python3 scripts/start_backend_daemon.py     # 9091
python3 scripts/start_frontend_daemon.py    # 8765

# 2. 子产品启动（按需）
cd /Users/hua/Documents/myself/MPD-myself
export LINGYAO_JWT_SECRET="test-secret-32-chars-long-aaaa"
python3 scripts/start_backend_daemon.py     # 8100 (HPD)
python3 scripts/start_frontend_daemon.py    # 3100 (HPD)
# 另起 daemon 跑 PORM 3190/8280

# 3. 端到端验证
# - 浏览器打开 http://localhost:8765
# - admin/admin123 登录
# - 点 HPD 卡片 → 应自动登录并跳 http://localhost:3100/（不是 /login）
# - 关闭浏览器再打开 http://localhost:3100 → 应仍处于登录态（cookie 有效）
# - 在主站用 a_user1 登录 → 点 HPD → 应弹"无权限"模弹（不跳转）
```

### 5.2 prod 环境（待主人确认后细化）

需要：域名 + HTTPS + 跨域 cookie + 生产 secret 注入。

---

## 6. 工作量汇总与实施顺序

| 子产品 | 工作量 | 优先级 | 依赖 |
|---|---|---|---|
| HPD/MPDM | 0.5 人日 | P0 | 共享 JWT secret 配置 |
| AIDD | 0.5 人日 | P0 | 前端勘察 0.1 人日 |
| PORM 明枢 | 0.5 人日 | P0 | 与 HPD 共享后端代码可复用 |
| Dinfo 辰录 | 0.3 人日 | P1 | Vue Router 改造 |
| GEOM 棱镜 | 0.3 人日 | P2 | 飞书登录兼容 |
| **合计** | **2.1 人日** | | |

### 推荐实施顺序

1. **Day 1 上午**：HPD（最简单，模板直接套）
2. **Day 1 下午**：PORM（与 HPD 同仓，可复用部分代码）
3. **Day 2 上午**：AIDD（含勘察）
4. **Day 2 下午**：Dinfo（Vue Router beforeEach）
5. **Day 3 上午**：GEOM（不动飞书）
6. **Day 3 下午**：端到端联调（主网站 5 张卡片 SSO 全验证）

---

## 7. 风险提示

1. **共享 JWT secret 泄漏风险**：任意子产品被攻破 = 主站 token 可伪造
   - 缓解：缩短 token 有效期（主仓当前 86400s = 24h，建议 SSO token 单独 7200s）
   - 缓解：子产品只读 `user_id`/`username`，不传 `role`/`admin` 等高敏 claim

2. **跨域 cookie 在 dev/local 不工作**：
   - localhost 没有 `.localhost` 子域概念
   - 解决：dev 模式 `Domain=None` + `SameSite=Lax`（已实现）
   - 生产用 `.lingyao.cn` 子域共享

3. **SSO 失败兜底**：
   - 子产品保留本地用户名/密码登录入口
   - SSO 失败时引导用户走本地登录（不阻塞）

4. **审计追踪**：
   - 每个 SSO 登录写入子产品 `audit_log` 表，标 `source='lingyao'`
   - 主仓侧也记录 `SUBTASK_ENTER` 审计（已实现）

---

## 8. 不在本方案范围

以下事项不在本阶段方案内，需要主人后续决策：

- 子产品数据库是否合并到主仓 PostgreSQL（不建议，破坏独立仓架构）
- 子产品 SSO token 是否需要 PKCE / OAuth 2.0 完整流程（当前为简化版 JWT，足够内部使用）
- 主站与子产品的 session 互踢（主站退出登录时子产品是否同步）
- 跨子产品跳转（AIDD → HPD）是否需要带 token（本期不做，子产品之间独立登录）

---

## 9. 文件清单

实施时将在每个子产品仓创建/修改：

| 子产品 | 新增文件 | 修改文件 |
|---|---|---|
| HPD | `backend/app/api/sso.py`<br>`frontend/src/pages/SsoCallback.tsx` | `backend/app/main.py`<br>`frontend/src/App.tsx`<br>`backend/app/services/user_service.py` |
| AIDD | 同上 | 同上 |
| PORM | 同上 + `frontend/src/pages/hoyuan/SsoCallback.tsx` | 同上 |
| Dinfo | `backend/app/api/sso.py`<br>`frontend/src/views/SsoCallback.vue` | `backend/app/main.py`<br>`frontend/src/router/index.ts` |
| GEOM | `geo-backend-py/app/api/sso.py`<br>`geo-frontend/src/pages/SsoCallback.vue` | `geo-backend-py/app/main.py`<br>`geo-frontend/src/router/index.js` |

预计每个子产品 2-3 个文件改动，单仓 commit 即可独立上线。
