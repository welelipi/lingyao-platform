# CONTEXT_DUMP.md — 完整上下文转储

> 新窗口必读章节：§3（12 项决策）、§4（架构细节）、§5（Phase 1–11 详细日志）、§6（未完成清单）、§7（风险）。

## 1. 主人与项目身份

- **主人**：金赛药业数据分析师 + 产品经理（昵称"钊审财"），关注金赛 CACS 销量预测、GEO 平台、投资组合。
- **花卷**：WorkBuddy AI 助手，主人对话风格"严谨专业"，**长对话期间主人会主动关注 token 用量**。
- **工作环境**：macOS（darwin）/ zsh，IDE 主题 light，WorkBuddy workspace = `/Users/hua/Documents/myself/凌瑶`（主项目，2026-08-11 14:25 起正式接管 OEG；OEG 物理资产已重定位至 `_external/OEG/`，访问软链 `products/geo-platform`）。

## 2. 项目家族

- **OEG**（金赛药业 GEO 品牌智能体）— 主人主要产品。
- **AIDD**（AI 研发反馈）— 主人 4 模块产品矩阵的第二个。
- **CACS**（医院潜力预测）— 4 模块产品的第三个，**未启动**。
- **协作智能体**（项目管理）— 4 模块产品的第四个，**未启动**。
- **凌瑶智数**（SaaS 总入口）— 14:04 主人明确升为主项目。

## 3. 12 项 SaaS 化决策（不可逆/回滚成本高）

> 来源：`/Users/hua/Documents/myself/凌瑶/OEG/handoff/2026-08-11.md`（原 OEG memory 已同步到凌瑶下）§"OEG 平台 SaaS 化决策与 Phase 1 落地"。

| # | 决策项 | 选择 | 备注 |
|---|---|---|---|
| 1 | 租户模型 | **A：一公司一账号** | 不是多账号/部门树 |
| 2 | 历史数据归属 | A：全部回填到种子公司 | 数据迁移方向已锁 |
| 3 | 邀请机制 | A：链接邀请 | 不是邀请码/审批流 |
| 4 | 登录方式 | **C+账号密码（双轨）** | 飞书 SSO + 账号密码并存，license.features.auth_mode 控制 |
| 5 | 计费/订阅 | A：暂不做 | license 表未建 |
| 6 | 审计粒度 | **B：新建 company_audit_log** | 4 张审计表之一 |
| 7 | 私有化深度 | **A：数据库独立 + 服务独立** | 客户可单机部署 |
| 8 | SaaS 控制中心部署 | A：同库新增 platform-hub 子模块 | 不独立库 |
| 9 | 私有化升级方式 | B：客户手动触发 + 一键升级工具 | install-saas.sh 已就绪 |
| 10 | 离线运行能力 | B：离线可用 + 定期联网同步 | 飞书 OAuth 走联网 |
| 11 | 许可证模式 | A：永久 license + 年维护费 | license 表字段已规划 |
| 12 | 白标支持 | B：仅自定义主题色 | 不做完整白标 |

## 4. 架构细节

### 4.1 多租户实现（绝对不能动）

- **核心机制**：`TrackedConnection` 包装 `sqlite3.Connection`，monkey-patch `execute()`，从 contextvars 读取 `cid`，透明改写 SQL `WHERE company_id=?`。
- **关键文件**：
  - `OEG/geo-platform/geo-backend-py/app/db_patch.py`
  - `OEG/geo-platform/geo-backend-py/app/tracked_db.py`
  - `OEG/geo-platform/geo-backend-py/main.py`（`@app.middleware("http")` 从 Authorization 解析 JWT 注入 cid）
- **业务 router 零改动**：所有租户隔离都在数据访问层，业务代码看到的就是"自动按公司过滤"。

### 4.2 跨线程 contextvars 修复

- **bug 现象**：anyio 4.x 线程池复用 worker，`threading.local` 残留上一次请求的 cid，导致 cid=99 用户看到 cid=1 数据。
- **修复**：去掉 tl 优先逻辑，**仅用 `contextvars`**（anyio 通过 `copy_context()` 自动传递）。
- **验证**：T1 (cid=1) 看到 18 条 reports，T99 (cid=99) 看到 0 条。

### 4.3 业务代码未改文件清单（红线）

```
geo-backend-py/app/routers/brands.py
geo-backend-py/app/routers/diagnosis.py
geo-backend-py/app/routers/monitoring.py
geo-backend-py/app/routers/engine.py
geo-backend-py/app/routers/real_data.py
geo-backend-py/app/routers/collect.py
geo-backend-py/app/routers/competitor_intel.py
geo-backend-py/app/routers/content.py
geo-backend-py/app/routers/patient_insight.py
geo-backend-py/app/routers/traceback.py
geo-backend-py/app/routers/advice.py
geo-backend-py/app/routers/feishu_auth.py
geo-backend-py/app/routers/auth.py
geo-backend-py/app/routers/admin.py
geo-backend-py/app/services/*
geo-backend-py/geo_engine/*
```

### 4.4 凌瑶智数 SSO 协议

```
{product_url}/?token={JWT}&from=lingyao&company_id={N}&redirect={path}
```

- `token`：凌瑶签发的 JWT（payload: `sub` / `uid` / `role` / `cats` / `cid` / `iat` / `exp` / `iss=lingyao-portal`）。
- `from`：`lingyao`（固定），标识来源。
- `company_id`：当前公司 ID。
- `redirect`：登录后跳到具体页面，默认 GEO `/action-center`、AIDD `/login.html`。

### 4.5 统一用户中心

- **物理数据库**：`OEG/geo-platform/geo-backend-py/data/geo.db`（47 张表）。
- **凌瑶 portal 直接读**：sys_user / company / company_user / company_audit_log。
- **共享 JWT secret**：`OEG/geo-platform/geo-backend-py/data/.jwt-secret`（dev 持久化）。
- **生产化**：`GEO_JWT_SECRET` 环境变量替换（待 P0）。

## 5. Phase 1–11 详细日志

### 5.1 Phase 1 — 数据库基础设施
- 备份：`oeg_pre_saas_phase1_20260811_095208.db`（206.64 MB，integrity=ok）
- 4 张新表：`company` (15 字段) / `company_user` (9 字段) / `company_invitation` (11 字段) / `company_audit_log` (11 字段)
- 扩展 sys_user：`current_company_id` / `phone` / `login_methods` / `email_verified` / `last_active_at`
- 业务表 34 张 ALTER TABLE ADD COLUMN company_id NOT NULL DEFAULT 1
- 4 张大表加复合索引：`collect_record` (28K) / `traceback` (32K) / `patient_insight` (1.4K) / `claim_verification` (4.4K)
- 种子公司：company_id=1 = "OEG 演示公司"，deployment_mode=saas，license_plan=enterprise
- 23 个 sys_user 全部加入种子公司，role 沿用 super_admin(1) / senior_operator(11) / operator(11)
- 代码改动：`app/token_utils.py`（issue_token 加 company_id）/ `app/permissions.py`（CurrentUser.company_id）/ `app/routers/{auth,feishu_auth}.py`（双轨登录带入 current_company_id）

### 5.2 Phase 2 — 数据隔离层
- `db_patch.py`：TrackedConnection SQL 改写 + contextvars
- `main.py`：`@app.middleware("http")` 从 Authorization 解析 JWT 注入 cid
- 跨公司 100% 隔离：5 张关键业务表 100% 归属 company_id=1
- 关键 bug 修复：anyio 4.x 跨线程 contextvars（去掉 tl）

### 5.3 Phase 3 — 用户管理 + 邀请链接（14 API）
- `app/routers/companies.py`：GET/POST /companies + /users + /invitations
- 端到端验证：创建公司（id=2）→ 生成邀请 → boss 接受 → 角色变更

### 5.4 Phase 4 — 前端公司 / 用户 / 角色管理 UI
- `CompanyMgmt.vue`（14952 字节）— 综合管理页（tabs：公司/用户/邀请）
- `InvitationAccept.vue`（4515 字节）— 接受邀请页
- `api/index.js` 追加 `companyApi`
- `router/index.js` 加 `/admin/companies` + `/invitation/:token`

### 5.5 Phase 5 — Platform-Hub SaaS 控制中心（5 API）
- `app/routers/platform_hub.py`：dashboard / companies / users / audit-logs / licenses
- 验证：2 公司 / 23 用户 / 2 super_admin

### 5.6 Phase 6 — 私有化打包
- `install-saas.sh`（9648 字节）— 一键安装
- `docker-compose-saas.yml`（7644 字节）— 3 服务编排
- `SAAS-DEPLOY.md`（8182 字节）— 运维手册

### 5.7 Phase 10 — GEO 视觉重塑（7 子任务）
- 10.1：`src/styles/tokens.scss` 47 个 CSS 变量
- 10.2：`src/styles/theme.scss` + `src/assets/global.css` 完全重写
- 10.3：`src/styles/layout.scss`（Sidebar/Topbar/Login 全深色）
- 10.4：`components/WelcomeBanner.vue`（嵌入 ActionCenter 顶部）
- 10.5：`utils/sso.js`（`consumeSsoTokenFromUrl()` 启动时吸收 token）
- 10.6：`router/index.js`（加 super_admin_only meta + /admin/platform-hub 占位）
- 10.7：Vite dev + 后端实跑验证

### 5.8 Phase 11 — 凌瑶智数门户化（6 子任务）
- 11.1：物理重整（`凌瑶/.workbuddy/` + `products/` 符号链接 + 同步 OEG memory）
- 11.2：凌瑶升级为真正登录门户
  - `凌瑶/portal-backend-py/main.py`（FastAPI :8765）
  - `凌瑶/website/portal.html`（4 模块选择页）
  - 修改 `凌瑶/website/index.html` 的"登录"按钮 → /portal
- 11.3：统一用户中心（隐式完成：portal 直接读 GEO sys_user + 共享 JWT secret）
- 11.4：GEO 接受 SSO（`sso.js consumeSsoTokenFromUrl()` 验证 OK）
- 11.5：AIDD 接受 SSO（仅前端层，`login.html` 注入 `consumeSsoFromLingYao()`）
- 11.6：4 条链路端到端验证通过

## 6. 未完成清单（详细）

### 6.1 P0 — 生产前必做
1. **GEOadmin 密码改强密码**（当前 `lingyao@2026`）。
2. **AIDD 后端 JWT 改造**：写 `JwtAuthInterceptor.java` + 配置 `application.yml` 共享 secret。
3. **HTTPS / TLS 自动签发**：私有化客户第一个问题。
4. **数据库自动备份 cron** + 保留策略。
5. **升级 / 迁移机制**：`upgrade.sh` + schema migration 版本号。

### 6.2 P1 — 体验与平台化
1. **审计日志前端 UI**（4 条审计日志在表里，前端看不到）。
2. **Platform-Hub dashboard 前端**（5 个 API 就绪，前端缺）。
3. **同一用户多公司身份切换**（`sys_user.current_company_id` 字段已有，前端 + API 缺）。
4. **CACS / 协作智能体模块占位骨架**（凌瑶 portal 卡片是灰的）。

### 6.3 P2 — SaaS 化深水区
1. **license 表 + 订阅管控**（数据库中没建 license 表，platform_hub /licenses 接口底层缺失）。
2. **max_users 强制执行**（`company.config` 已有 max_users，但没代码检查）。
3. **跨租户 API 限流**。
4. **Webhook / 事件总线**。
5. **白标 / 自定义品牌**。
6. **GDPR 合规删除**。
7. **Kubernetes manifests / Helm chart**。

### 6.4 视觉细节微调（待主人指点）
- ActionCenter.vue 顶部的 WelcomeBanner 颜色 / 字号 / 文案
- 凌瑶 portal 4 模块卡片的图标 / 状态标签
- 登录态与未登录态的过渡动画

## 7. 风险与红线

### 7.1 已修复但要警惕
- **跨线程 contextvars**：已修。引入新并发（thread pool）时重测。
- **JWT secret 漂移**：dev 环境持久化到 `.jwt-secret` 文件，进程重启不丢；生产改环境变量。

### 7.2 当前未做但高风险
- **AIDD 后端不认 token**：当前 SSO 只是 UI 跳板，AIDD 后端仍用 X-Tenant-Code 模式；用户身份不真正通。
- **GEOadmin 密码明文**：主人已默认这是 dev/test 凭证，生产前必改。
- **数据隔离主表全用 `WHERE company_id=?`，JOIN 子表靠开发者手动**——复杂 SQL 容易漏。
- **子查询 / 标量子查询**：`db_patch.py` 当前只改最外层主 FROM，嵌套 SELECT 不会自动加 `company_id`，新写 SQL 时要小心。

### 7.3 绝对不能动
- 凌瑶 portal 共享 GEO SQLite 的事实（动了就破统一用户中心）。
- 12 项 SaaS 决策（已记入 memory，主人确认过）。
- 凌瑶智数视觉规范（深色 + 青色 + 玻璃态 + Noto Sans SC + Inter）。

## 8. 命令与文件位置速查

参见 `RUN_COMMANDS.md`。

---

_完整上下文转储，与 PROJECT_STATE.json 同步。_
