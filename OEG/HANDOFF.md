# OEG 任务 · 详细交接

> 移交人：旧窗口花卷  
> 移交时间：2026-08-11 14:04 GMT+8  
> 接收人：新窗口花卷（在本目录的 OEG 任务下重启）

## 0. TL;DR

1. **项目**：金赛药业 GEO 品牌智能体 SaaS 平台（OEG / GEO 智策）。
2. **现状**：SaaS 化 Phase 1–6 全部完成、凌瑶智数门户化（Phase 11）完成、GEO 视觉重塑（Phase 10）完成。
3. **4 个服务正在跑**：凌瑶 8765、GEO 后端 9090、GEO 前端 9180、AIDD 前端 9191。
4. **登录**：`GEOadmin` / `lingyao@2026`（已重置）。生产前必改回强密码。
5. **下一步候选**：见 §6，按主人 14:04 的口吻，下一条主线是"继续推进 OEG"——具体路径新窗口要先问主人。

## 1. 架构总览

```
凌瑶智数（WorkBuddy 主 Workspace）           ← 主人 14:04 之前下达的主项目
├── README.md                                 ← 主项目说明
├── .workbuddy/                               ← WorkBuddy 主配置 + memory
├── portal-backend-py/main.py                 ← 凌瑶门户后端（FastAPI，端口 8765）
├── website/
│   ├── index.html                            ← 营销官网（已修改"登录"按钮 → /portal）
│   ├── portal.html                           ← ★ 4 模块选择门户（Phase 11.2）
│   └── css/style.css                         ← 视觉规范源
├── shared/                                   ← 预留共享代码目录
├── products/
│   ├── geo-platform → /Users/hua/Documents/myself/凌瑶/_external/OEG/geo-platform
│   └── aidd        → /Users/hua/Documents/myself/AIDD/ai-project-copilot
└── OEG/                                      ← ★ 新建：本任务目录
    ├── START_HERE.md                         ← 新窗口第一站
    ├── HANDOFF.md                            ← 你正在读
    ├── PROJECT_STATE.json                    ← 机器可读快照
    ├── handoff/                              ← 完整上下文转储
    │   ├── CONTEXT_BRIEF.md
    │   ├── CONTEXT_DUMP.md
    │   ├── RUN_COMMANDS.md
    │   └── CHANGELOG_HANDOFF.md
    └── next_steps.md                         ← 推荐下一步（待新窗口确认）
```

> 注：`OEG/geo-platform` 等子目录**没有**复制——保持符号链接，避免双份。

## 2. 当前运行状态

> **快照时间**：2026-08-11 14:04。**新窗口接管时务必重新校验**（参见 `RUN_COMMANDS.md`）。

| 服务 | 端口 | PID | 验证命令 |
|---|---|---|---|
| 凌瑶 portal 后端 (FastAPI) | 8765 | 37891 | `curl --noproxy '*' -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:8765/api/health` |
| GEO 后端 (FastAPI) | 9090 | 31375/31378/31379 | `curl --noproxy '*' -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:9090/docs` |
| GEO 前端 (Vite) | 9180 | 34049 | `curl --noproxy '*' -s -o /dev/null -w '%{http_code}\\n' http://localhost:9180/` |
| AIDD 前端 (python http.server) | 9191 | 37811 | `curl --noproxy '*' -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:9191/login.html` |

### 2.1 关键数据

- **数据库**：`/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/geo.db`（47 张表，206 MB）
- **SQLite 备份**：`/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/backups/oeg_pre_saas_phase1_20260811_095208.db`（integrity=ok）
- **JWT secret 位置**：`/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/.jwt-secret`（dev 环境持久化，凌瑶 portal 与 GEO 后端共享）
- **种子公司**：company_id=1 = "OEG 演示公司"，deployment_mode=saas，license_plan=enterprise
- **种子用户**：`GEOadmin` / `lingyao@2026`（已重置）；role=super_admin，cid=1
- **SaaS 表行数**：
  - `sys_user` 23 / `company` 2 / `company_user` 25 / `company_invitation` 1
  - `user_audit_log` 159 / `company_audit_log` 4

## 3. 已完成工作（Phase 1–11）

### 3.1 SaaS 化 6 大阶段（OEG 内）
- **Phase 1**：数据库初始化（4 张新表 + sys_user 扩展 + 37 张业务表加 company_id）— 全部完成。
- **Phase 2**：TrackedConnection 数据隔离层（contextvars + Middleware）— 全部完成，跨公司 100% 隔离。
- **Phase 3**：用户管理 + 邀请链接接口（14 个 API）— 全部完成。
- **Phase 4**：前端公司 / 用户 / 角色管理 UI（CompanyMgmt.vue + InvitationAccept.vue）— 全部完成。
- **Phase 5**：Platform-Hub SaaS 控制中心（5 个 dashboard API）— 全部完成。
- **Phase 6**：私有化打包（install-saas.sh + docker-compose-saas.yml + SAAS-DEPLOY.md）— 全部完成。

### 3.2 视觉重塑（Phase 10）
- 抽取灵瑶智数（127.0.0.1:8765）设计 tokens：`tokens.scss` 47 个 CSS 变量。
- 全局主题改深色（`#050F22` 背景 + `#00D4FF` 品牌色 + 玻璃态）。
- `WelcomeBanner.vue` 嵌入 ActionCenter 顶部。
- `Placeholder.vue` + `/admin/platform-hub` 占位路由，super_admin 视角架构预留。
- `package.json` 新增 `sass`（dev）依赖。

### 3.3 凌瑶智数门户化（Phase 11）
- **物理重整**：`凌瑶/.workbuddy/` 主配置 + `products/` 符号链接（OEG 原路径零破坏）。
- **门户后端**：`凌瑶/portal-backend-py/main.py` 共享 GEO 数据库与 JWT secret。
- **门户页**：`凌瑶/website/portal.html` 4 模块卡（GEO / AIDD active；CACS / 协作智能体灰）。
- **统一用户中心**：凌瑶签发 JWT → GEO 后端原生接受。
- **SSO 协议**：`{url}/?token=&from=lingyao&company_id=&redirect=`。
- **GEO 接收**：`sso.js consumeSsoTokenFromUrl()` 启动时自动吸收。
- **AIDD 接收**：`frontend/login.html` 注入 `consumeSsoFromLingYao()`，前端层 SSO 跳过登录。
- **端到端**：4 条链路验证通过（凌瑶登录 / 凌瑶→GEO / 凌瑶→AIDD / 跨公司隔离）。

## 4. 关键文件清单（按模块）

### 4.1 凌瑶智数主项目
- `凌瑶/README.md`（已更新进度勾选）
- `凌瑶/.workbuddy/memory/2026-08-11.md`（已从 OEG memory 同步）
- `凌瑶/portal-backend-py/main.py`（FastAPI 入口、bcrypt 校验、4 模块配置）
- `凌瑶/website/portal.html`（4 模块门户页）
- `凌瑶/website/index.html`（已替换"登录"按钮 → `/portal`）
- `凌瑶/products/{geo-platform,aidd}`（符号链接 → 原 OEG/AIDD 目录）

### 4.2 GEO 平台（OEG 内）
- 后端 SaaS 化核心：
  - `OEG/geo-platform/geo-backend-py/app/db_patch.py`（TrackedConnection SQL 改写 + contextvars）
  - `OEG/geo-platform/geo-backend-py/app/tracked_db.py`
  - `OEG/geo-platform/geo-backend-py/app/routers/companies.py`（14 个 API）
  - `OEG/geo-platform/geo-backend-py/app/routers/platform_hub.py`（5 个 API）
  - `OEG/geo-platform/geo-backend-py/main.py`（中间件注入 + SaaS router 注册）
- 前端视觉重塑：
  - `OEG/geo-platform/geo-frontend/src/styles/{tokens,theme,layout}.scss`
  - `OEG/geo-platform/geo-frontend/src/assets/global.css`（完全重写）
  - `OEG/geo-platform/geo-frontend/src/components/WelcomeBanner.vue`
  - `OEG/geo-platform/geo-frontend/src/utils/sso.js`
  - `OEG/geo-platform/geo-frontend/src/router/index.js`（加 super_admin_only meta）
  - `OEG/geo-platform/geo-frontend/src/pages/Placeholder.vue`
- 私有化打包：`OEG/geo-platform/{install-saas.sh, docker-compose-saas.yml, SAAS-DEPLOY.md}`

### 4.3 业务代码（绝对不要动）
- `OEG/geo-platform/geo-backend-py/app/routers/` 下的所有业务 router（brands / diagnosis / monitoring / engine / real_data 等）：**SaaS 化期间一行未改**，所有租户隔离都在数据访问层透明实现。

### 4.4 AIDD（仅前端层 SSO）
- `AIDD/ai-project-copilot/frontend/login.html`：注入 `consumeSsoFromLingYao()` 函数，检测 URL `?from=lingyao` 自动跳过登录并跳 dashboard。
- **AIDD 后端 JWT 改造 = 未做**（Spring Boot + Tenant 多租户，无 JWT 拦截器）。

## 5. 凭据与安全

| 用途 | 用户名 | 密码 | 备注 |
|---|---|---|---|
| 凌瑶 portal 登录 | `GEOadmin` | `lingyao@2026` | 上一会话重置为方便验证，**生产前必须改** |
| GEO dev-token | （见主项目 token_utils） | — | 短 token 调试用 |
| AIDD tenant 登录 | `default-private` | — | AIDD 后端 X-Tenant-Code 模式 |
| JWT secret | dev 环境从 `data/.jwt-secret` 文件读取 | — | 凌瑶 portal 与 GEO 后端共用此 secret |

## 6. 未完成与待决策（按优先级）

### 6.1 P0 — 生产前必做
1. **GEOadmin 密码改回强密码**（当前是测试明文 `lingyao@2026`）。
2. **AIDD 后端 JWT 改造**：写 `JwtAuthInterceptor.java` + 配置 `application.yml` 共享 secret，凌瑶签发的 token 才能真正进 AIDD 后端。
3. **HTTPS / TLS 自动签发**：私有化客户第一个问题。
4. **数据库自动备份 cron** + 保留策略。
5. **升级 / 迁移机制**：`upgrade.sh` + schema migration 版本号。

### 6.2 P1 — 体验与平台化
1. **审计日志前端 UI**（数据已有，UI 缺）。
2. **Platform-Hub dashboard 前端**（API 已有，UI 缺）。
3. **同一用户多公司身份切换**（`sys_user.current_company_id` 字段已有，前端 + API 缺）。
4. **CACS / 协作智能体模块占位骨架**（凌瑶 portal 卡片是灰的）。

### 6.3 P2 — SaaS 化深水区
1. License / 订阅管控（`license` 表尚未建）。
2. License 限制执行（`max_users` 已在 company.config，但没代码检查）。
3. 跨租户 API 限流。
4. Webhook / 事件总线。
5. 白标 / 自定义品牌。
6. GDPR 合规删除。
7. Kubernetes manifests / Helm chart。

## 7. 新窗口的第一天建议

1. **问主人**："先继续 Phase 12 的哪条线？AIDD 后端 JWT？审计 UI？HTTPS 打包？"
2. **跑端到端冒烟**：参见 `RUN_COMMANDS.md`，确认 4 个服务都活着。
3. **校验改动范围**：`git -C /Users/hua/Documents/myself/凌瑶/products/geo-platform status --short`，确认主人下一条指令是落在哪些文件上。
4. **写新 memory**：在新工作区 memory 目录里追加新一天的日志。

## 8. 历史会话关键决策（不可逆或回滚成本高）

- 12 项 SaaS 决策（参见 `OEG/handoff/CONTEXT_DUMP.md` §3）：多租户模型 = "一公司一账号"；license = 永久 + 年维护；私有化 = 数据库独立 + 服务独立。
- 视觉规范 = 凌瑶智数深色青色品牌（`#00D4FF` + `#050F22`）。
- 多租户实现 = TrackedConnection monkey-patch 透明拦截，**业务 router 一行未改**。
- 凌瑶 = 主 workspace；OEG / AIDD = 子任务（符号链接模式，不复制）。

## 9. 已知风险与红线

1. **跨线程 contextvars**：已修复（去掉 `threading.local`，仅用 `contextvars`）。如果未来引入 anyio worker pool 之外的并发，要重新验证。
2. **JWT secret 持久化**：dev 环境写到 `data/.jwt-secret`，生产应改用 `GEO_JWT_SECRET` 环境变量。
3. **AIDD 后端不认 token**：当前 SSO 只是 UI 跳板，AIDD 后端实际仍用 X-Tenant-Code 模式；数据拉取不会失败，但用户身份不真正通。
4. **凌瑶 portal 共用 GEO SQLite**：物理数据仍在 OEG 数据库里，凌瑶只是入口 UI；未来要拆凌瑶独立库时需迁移。
5. **GEOadmin 密码明文**：上一会话为了快速验证而重置，生产前必须改。

---

_本文件由 2026-08-11 14:04 旧窗口生成，与 PROJECT_STATE.json 同步。_
