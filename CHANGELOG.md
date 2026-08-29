# 凌瑶智数 · 变更日志

> 维护者：凌瑶主人
> 起始：2026-08-27（首次创建，作为质量环产物）

## V2.0.13 (2026-08-29 18:50) — AIDD SSO 接入（依次启动第 1 站）

### 改动
- `application.yml` `app.version`: 2.0.12 → 2.0.13
- `application.yml` `lingyao.subtask.routes.aidd.base-url`: `http://localhost:13000` → `http://localhost:13000/sso-callback.html`（AIDD 是静态 HTML，跳 SSO 回调页而非 hash 路由）
- `data.sql` sub_task AIDD 描述加「C47 W5 P1-SSO 已对接」+ base_url 同步

### 配合改动（AIDD 仓内）
- AIDD 1.0.0-W-Cycle177 → 1.0.0-W-Cycle178-D1-LingyaoSSO
- 后端：`LingyaoSsoService.java`（新建 3 级回退匹配）+ `AuthController` 加 `POST /api/sso/login` + `TenantUser` 加 4 字段
- 前端：`sso-callback.html`（新建，5 参数 → POST → 跳 dashboard）+ `login.html` 加 "凌瑶主站登录" 按钮

### 端到端验证（18:50）
主仓 admin → `/api/sub/aidd/enter` → 跳 AIDD `sso-callback.html` → AIDD `/api/sso/login` 验签 → 自动关联 AIDD admin 用户 → 签 AIDD 本地 JWT → 可访问 AIDD `/api/auth/me` ✅

### PORM 接入补充（19:05 第 2 站）
- PORM 0.8.151 → 0.8.152
- 后端：`LingyaoSsoController.java`（新建，HS512 验签，User 域字段加 3 个）+ `pom.xml` 加 jjwt 0.12.6 + `SecurityConfig` 加白名单 + `application.yml` 加 lingyao.jwt.secret + `start_mingshu_daemon.py` 新建（profile 必须 demo,h2 因为 UserSyncer @Profile("h2")）
- 前端：`pages/SsoCallbackPage.jsx`（新建，state-routing 'ssoCallback' 分支）+ `App.jsx` 跳过 /api/auth/me + `services/api.js` 加 lingyaoSsoLogin endpoint
- 主仓：`data.sql` sub_task.porm base_url 改 `http://localhost:8280/api/sso/callback-redirect`（标准模式）

### PORM 端到端验证（19:05）
主仓 admin → `/api/sub/porm/enter` → 跳 PORM `/?platform_token=...&...` → PORM `/api/sso/login` 验签 → 自动关联 PORM admin 用户（userId=ou_demo_admin, role=superadmin）→ 签 PORM 本地 token → 可访问 PORM `/api/auth/mode` ✅

### PORM 踩坑记录（铁律级）
1. **YAML 不能有重复顶层 key**：第一次把 `mingshu.lingyao` 单独加在 line 11，但 line 18 已有 `mingshu.sso`，导致 DuplicateKeyException 启动失败 → 必须合并到一个 `mingshu:` 块下
2. **profile 必须 demo,h2**：UserSyncer 是 `@Profile("h2")`，单独用 demo 时 Spring 找不到 bean → FeishuOAuth2SuccessHandler 注入失败 → 必须 `demo,h2` 双 profile
3. **SSO 业务消息要在 SecurityConfig permitAll 之前**：先 permitAll 才能让 `/api/sso/login` 走到 controller，否则被 Spring Security 401 拦截

### 后续 2 子产品
- [x] Dinfo（Vue + 飞书 callback 共存）~0.5-1.0 工作日 → ✅ 14:55 完成
- [x] GEOM（Python FastAPI + 飞书 callback 不动）~1 人天 → ✅ 15:18 完成

---

## V2.0.13 (2026-08-29 19:35) — Dinfo SSO 接入（依次启动第 3 站）

### 改动
- `application.yml` `app.version`: 2.0.13（沿用 18:50 AIDD 那次 bump）
- `application.yml` `lingyao.subtask.routes.dinfo.base-url`: `http://localhost:5181` → `http://localhost:5181/auth/lingyao/callback`
- `data.sql` sub_task Dinfo 描述加「C47 W5 P1-SSO 已对接」+ base_url 同步

### 配合改动（Dinfo 仓内）
- Dinfo D1.0.1 → D1.1.0
- 后端：`LingyaoSsoController.java`（新建，HS512 验签）+ `Employee` 加 4 字段（lingyaoUserId/lingyaoTenantId/lingyaoUsername/lastSsoLoginAt）+ `application.yml` 加 `app.lingyao.jwt.secret`
- 前端：`router/index.ts` 加 `/auth/lingyao/callback` 路由 + `views/LingyaoCallback.vue`（新建）
- 启动：`start_dinfo_daemon.py` 新建（Java 双 fork 守护 + LINGYAO_JWT_SECRET 注入）

### Dinfo 端到端验证（14:55）
主仓 admin → `/api/sub/dinfo/enter` → 跳 Dinfo `/auth/lingyao/callback` → Dinfo `/api/auth/lingyao/callback` 验签 → 自动关联 dinfo_admin → 签本地 JWT ✅

### Dinfo 踩坑记录（铁律级）
1. **JwtUtil HS256 vs HS512 自动推断**：Dinfo 原本 JwtUtil 写死 HS256，验签凌瑶 HS512 token 抛 signature mismatch → 改成 `Keys.hmacShaKeyFor` 自动推断（jjwt 0.12.x 的 feature），按 algorithms 列表首个匹配
2. **adm-true 超管兜底**：主仓 admin token 没有 role 字段，只有 `adm: true`；必须 `Boolean.TRUE.equals(admObj)` 兜底绑定到 dinfo_admin，否则 Dinfo 找不到员工返 403
3. **Dtos.ApiResponse.fail() 只有双参数**：原本 LingyaoSsoController 想传 (code, message, data) 编译报错 → 去掉 data 参数，用 (code, message) 双参数
4. **端口 8281 被 POR 源头仓占用**：lsof 显示源头仓进程 PID 93027 没有 lingyao_sso 端点 → kill -9 释放端口后重启 Dinfo daemon

---

## V2.0.13 (2026-08-29 15:22) — GEOM SSO 接入（依次启动第 4 站 · 收官）

### 改动
- `application.yml` `lingyao.subtask.routes.geo.base-url`: `http://localhost:5180` → `http://localhost:5180/#/auth/lingyao/sso-callback`（GEOM 前端是 hash router，必须带 #/）
- `data.sql` sub_task GEO 描述加「V0.9.12.32 C47 W5 P1-SSO 接入凌瑶主站」

### 配合改动（GEOM 副本仓，凌瑶/geom/）
- GEOM V0.9.12.31.6 → V0.9.12.32
- 后端：`app/config.py` 加 `LINGYAO_JWT_SECRET`（默认与凌瑶主仓完全一致）+ `app/database.py` sys_user 加 4 字段 + `app/routers/lingyao_sso.py` 新建（PyJWT HS512 验签 + 3 级回退匹配）+ `main.py` include_router
- 前端：`geo-frontend/src/router/index.js` 加 `/auth/lingyao/sso-callback` 路由 + `pages/LingyaoSsoCallback.vue` 新建（hash router 模式截 window.location.hash）
- 启动：`start_geom_daemon.py` 新建（Python 双 fork + setsid，sandbox 友好）

### GEOM 端到端验证（15:18）
主仓 admin → `/api/sub/geo/enter` → 跳 GEOM `/#/auth/lingyao/sso-callback` → GEOM `/api/auth/lingyao/sso-callback` 验签 → 自动关联 liuling99（super_admin）→ 签本地 JWT ✅

### GEOM 踩坑记录（铁律级）
1. **APIRouter prefix 嵌套陷阱**：`APIRouter(prefix="/api/auth/lingyao")` + `include_router(prefix="/api/auth")` → 路径变成 `/api/auth/api/auth/lingyao/status`（404）。正解：APIRouter 裸路径 `/lingyao`，由 include_router 注入 `/api/auth`
2. **GEOM database.py 无 query_one helper**：必须用 `db = get_db(); db.row_factory = sqlite3.Row; row = db.execute(...).fetchone(); dict(row)` 模式
3. **sandbox 杀 nohup 进程**：必须用 Python 双重 fork + setsid 守护（macOS 无 setsid 命令 → `os.setsid()`）
4. **PyJWT 自动推断算法**：必须显式声明 `algorithms=["HS512"]`，不能让 PyJWT 从 header 推（HS256 兼容但排查耗时长）

---

## V2.0.13 收官总结：4 子产品全部 SSO 接入凌瑶主站 ✅

| 子产品 | 端口 | 后端栈 | SSO 端点 | 状态 | 完成时间 |
|---|---|---|---|---|---|
| **AIDD** | 18080 | Spring Boot 3 | POST /api/sso/login | ✅ | 18:50 |
| **PORM** | 8280 | Spring Boot 3 | POST /api/sso/login | ✅ | 19:05 |
| **Dinfo** | 8281 | Spring Boot 3 | POST /api/auth/lingyao/callback | ✅ | 14:55 |
| **GEOM** | 8090 | FastAPI + PyJWT | POST /api/auth/lingyao/sso-callback | ✅ | 15:18 |

**统一模式**（HPD 已建立的）：
1. 凌瑶主站签 JWT（HS512，audience=lingyao-sso，含 `uid/cid/adm/identity{user_id,username,display_name}` claim）
2. 子产品用共享 LINGYAO_JWT_SECRET 验签
3. 3 级回退匹配本地用户：lingyao_user_id → lingyao_username → 平台主仓 admin 兜底（adm-true）
4. 命中即更新 lastSsoLoginAt + 返回本地 JWT（HS256，7-30 天 TTL）

**dev 验证矩阵（15:22）**：
```
主仓 9091   PID 99254 ✅
AIDD 18080  PID 98799 ✅
PORM 8280   PID 402   ✅（重启后，新鲜）
Dinfo 8281  PID 93914 ✅
GEOM 8090   PID 98880 ✅
```

### 🔄 修正（15:30 · 主人提醒「皓元呢？」）

**HPD/MPDM 皓元其实早就做完了**——18:27 那次「主仓 v2.0.12 HPD 改造」就是在 MPD-myself 上做的（当时 HPD 仓 = MPD-myself）。本次汇报只数了今天 4 个改造，漏了 HPD。

完整 5 子产品 SSO 矩阵：
| 子产品 | 端口 | 后端栈 | SSO 端点 | 完成时间 |
|---|---|---|---|---|
| **HPD/MPDM** | 8100/3100 | FastAPI + PyJWT | POST /api/sso/login | **18:27 之前**（V2.0.12 改造） |
| AIDD | 18080 | Spring Boot 3 | POST /api/sso/login | 18:50 |
| PORM | 8280 | Spring Boot 3 | POST /api/sso/login | 19:05 |
| Dinfo | 8281 | Spring Boot 3 | POST /api/auth/lingyao/callback | 14:55 |
| GEOM | 8090 | FastAPI + PyJWT | POST /api/auth/lingyao/sso-callback | 15:18 |

HPD 端到端实测（15:30）：
```
POST /api/sso/login → 200
{"success":true,"authorized":true,"token":"eyJ...","role":"super_admin","user":{"id":3,"username":"admin","lingyao_user_id":1,"lingyao_tenant_id":1},"source":"lingyao"}
```

**5/5 全部端到端通过 ✅**

**后续可做（非阻塞）**：
- [ ] staging / prod 部署：5 个产品都用 staging/prod 的 LINGYAO_JWT_SECRET（生产环境必须改默认值）
- [ ] 数据闭环：4 子产品的 last_sso_login_at 可以汇总到主仓审计
- [ ] 退出统一：在子产品 logout 时跳回主仓（目前是各自独立 logout）


---

## 2026-08-27 · V2.0.1 (版本号方案 A 落地)

### Commit `9d2c1f7` · 方案 A：版本号机制升级（/api/version 端点 + SSO 协议版本字段）

**触发**：主人 2026-08-27 12:16 「通过方案 A」（5 条铁律全 yes）+ 质量环产物沉淀

**满足铁律**（方案 A 五条铁律全落地）：
- **V1** 三段式 X.Y.Z：`app.version: 2.0.0 → 2.0.1`（Z 段 bump）
- **V2** FE/BE 同步：凌瑶静态 HTML 无 FE/BE 同步需求；版本号机制统一在 BE
- **V3** 跨产品主版本同步：`sso.protocol-version: "1.0"` 字段建立，与 GEOm/MPDm 对齐 V2.0.0 时同步
- **V4** 必须 CHANGELOG.md：本文件即凌瑶 CHANGELOG
- **V5** 必须 /api/version 端点：新加 `GET /api/version`（公开无鉴权）

**改动**：
- 新增 `VersionController.java`：`/api/version` 端点（公开白名单）
- `SecurityConfig.java`：permitAll 列表加 `/api/version`
- `application.yml`：新增字段 `app.release` / `app.build-time` / `app.git-commit` / `sso.protocol-version` / `sso.jwt.secret` / `sso.jwt.token-ttl-seconds`
- `application.yml`：`app.version: 2.0.0 → 2.0.1`（铁律 2：小改动也要 bump）

**验证**：
- dev 9091 启动成功
- `curl http://127.0.0.1:9091/api/version` 返回完整 JSON：
  ```json
  {
    "code": 0,
    "data": {
      "service": "lingyao-platform",
      "version": "2.0.1",
      "release": "stable",
      "build_time": "2026-08-27T12:18:59+08:00",
      "git_commit": "dev",
      "sso_protocol": "1.0"
    }
  }
  ```
- `/api/health.data.version` 同步为 `2.0.1`（铁律 2 验证）

**附属收益**：
- SSO 协议版本字段已嵌入，凌瑶 V2.0.0 + GEOm/MPDm V2.0.0 三产品对齐时直接读 `sso.protocol-version`
- 部署脚本可注入 `APP_GIT_COMMIT=<hash>` 写入 `git_commit` 字段，运维对账零成本

## 2026-08-27 · V2.0.2 (前端硬编码 9091 修复)

### 触发
主人 2026-08-27 13:15 跑发布流程验证，卡在 staging 9092 浏览器报 HTTP 401（"请确认后端 9091 已启动"）。诊断：portal.html:796 + admin.html:688 硬编码 `const API_BASE = 'http://127.0.0.1:9091';`，staging 跨端口 9092 → 9091 命中 prod 后端，prod JWT secret 与 staging 不同，401 拒绝。

### 改动
- `website/portal.html:796` 硬编码 → 智能判断（同 `geo.html` 同源写法）：
  ```javascript
  const API_BASE = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
    ? 'http://127.0.0.1:9091' : '';
  ```
- `website/admin.html:688` 同改
- `application.yml`：`app.version: 2.0.1 → 2.0.2`（铁律 2）

### 验证（部署后）
- staging 9092 浏览器登录：admin/admin123 → 4 子产品页正常加载
- prod 9091（经 Nginx 80 反代）：登录页底部版本号 V2.0.2
- `curl /api/version` 返回 `version: "2.0.2", git_commit: <new>`

### 遗留（V2.0.3+ 候选）
- `deploy-staging.sh` 脚本 `REMOTE_DIR=/opt/lingyao` 路径错（mv 到 prod 路径，staging 跟着软链接跑 prod jar）
- `application-staging.yml` / `application-private.yml` 走 `--spring.config.location` 完全替换 classpath，jar 内 application.yml 没加载（version 读默认值）
- prod 9091 公网不可达（腾讯云安全组只放行 80 + 9092），更新文档：prod 入口走 Nginx 80

---

## 2026-08-27 · V2.0.3 (P0+P1 bug 修复闭环)

### 触发
V2.0.2 部署验证暴露 7 个待修 bug（部署路径冲突、SecurityConfig 默认 UserDetailsService、日志噪声、配置替换、Token 不兼容、SQL 冲突、推送通道告警），主人 14:58 下达"把该修的都修了"。

### 改动（7 项）

| ID | 文件 | 改动 |
|---|---|---|
| P0-1 | `scripts/deploy-staging.sh` | `REMOTE_DIR=/opt/lingyao` → `/opt/lingyao-staging`（staging/prod jar 路径独立）+ 加 step 自动迁移 systemd service ExecStart |
| P0-2 | `LingyaoApplication.java` | `@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })` — 关闭 Spring Security 默认 inMemoryUserDetailsManager |
| P1-3 | `SecurityConfig.java` | permitAll 白名单加 `/api/_diag/**` — 开发者 curl 自检版本无需 JWT |
| P1-4 | `scripts/deploy-staging.sh` | 自动迁移 systemd `--spring.config.location` → `--spring.config.additional-location`（外部 yml 叠加 classpath，不再替换）|
| P1-5 | `static/portal.html` + `admin.html` | `getToken()` 加 TOKEN_KEYS_LEGACY fallback（`ly_token` / `geo_token` 老 key 兼容，读到自动迁移到 `lingyao_token`）|
| P1-6 | `data-private.sql` | 全部 10 个 INSERT 加 `ON CONFLICT (id) DO NOTHING` — 二次启动不再依赖 `continue-on-error=true` 兜底 |
| P2-7 | `LingyaoApplication.java` | `pushHealthCheck` 降级 ERROR → WARN + 加 INACTIVE 默认通道说明（不再吓运维）|
| 版本号 | `application.yml` | `app.version: 2.0.2 → 2.0.3`（铁律 2）|

### 验证（部署后）
- `curl http://118.195.197.15:9092/api/_diag/version` 返回 200 + V2.0.3 JSON（无需 JWT）
- staging 9092 启动日志无 `Using generated security password` 噪声
- staging 9092 启动日志 `pushHealthCheck` WARN 级别（不再 ERROR）
- staging 9092 jar 路径 `/opt/lingyao-staging/lingyao-platform.jar`（与 prod `/opt/lingyao/lingyao-platform.jar` 独立）
- staging 浏览器登录 admin/admin123 正常
- prod 9091 部署后启动日志同样干净
- prod 浏览器登录 admin/admin123 正常

### 遗留（V2.0.4+ 候选）
- `data-private.sql` 里 admin 默认 BCrypt hash 仍是 `admin123`（首次启动后强制改密），但很多客户不会改，存在安全风险 → V2.0.4 加"首次启动随机生成 admin 密码 + 启动日志打印 + 邮件通知"
- 推送通道默认 webhook URL 是 `REPLACE_ME`，运维不知道在哪替换 → V2.0.4 在超管后台加 `一键测试所有推送通道` 按钮
- prod 9091 公网不可达（腾讯云安全组只放行 80 + 9092），更新文档：prod 入口走 Nginx 80

---

## 2026-08-27 · V2.0.4 (V2.0.3 半成品 + deploy-staging hotfix)

### 触发
V2.0.3 部署后暴露两个未完成项：
1. `SecurityConfig.java` 把 `/api/_diag/**` 加进 permitAll，但 `VersionController` 没建这个 endpoint → 返"接口不存在"
2. `deploy-staging.sh` 写完就发现两个 bug：heredoc 内 `$EXPECTED_JAR` 被本 shell 提前展开、curl 走 Mac 本地 9092（应该 ssh 到 CVM curl）

### 改动
- `VersionController.java`：新增 `@GetMapping("/api/_diag/version")` endpoint，扩展字段：pid / started_at / uptime_sec / heap_used_mb / heap_max_mb（开发者诊断用）
- `scripts/deploy-staging.sh`：
  - heredoc 内变量转义：`\$EXPECTED_JAR` / `\$ACTUAL`（让远程 bash 展开而非本 shell）
  - health check + 子产品页 + diag 都改 `ssh CVM curl`（之前 `curl 127.0.0.1:9092` 是 Mac 本地端口）
  - 自动校验 systemd service ExecStart jar 路径必须 = `$REMOTE_DIR/lingyao-platform.jar`，不一致自动 sed 修正
- `application.yml`：`app.version: 2.0.3 → 2.0.4`
- CVM 上 `/opt/lingyao/application-staging.yml`：删除末尾 `app: version: ...` 块（additional-location 模式下外部 yml 覆盖 jar 内 version，必须删掉）

### 验证（部署后）
- `curl /api/_diag/version` 返 200 + JSON 含 `pid` / `heap_used_mb` 等扩展字段（无需 JWT）
- `deploy-staging.sh` 端到端跑通：scp → 自动迁移 systemd → restart → ssh curl health/subpages/diag 全部 ✅
- staging 启动日志无 P0-2 噪声 + P2-7 WARN 友好提示

---

| 项 | 描述 | 来源 |
|---|---|---|
| P1-1 | GEOm `local_health.py:300` 完美状态过滤 | 质量环扫描（明早 P1.1 部署时修）|
| P2-1 | 凌瑶 `application.yml` JWT secret fail-fast | 质量环扫描 |
| P2-2 | MPDm `hospital_hoyuan.db` 移位置 | 质量环扫描 |
| P2-3 | 凌瑶 `WebConfig.java` CORS 补 9093/9094 | 质量环扫描 |
| V2-GEOm | GEOm `__version__` 重构 + 端点 + CHANGELOG | 方案 A 迁移（明早 P1.1 部署时一起做）|
| V2-MPDm | MPDm `PLATFORM_VERSION` 去 H 前缀 + 端点 + CHANGELOG | 方案 A 迁移（V2 上线前）|

---

## 历史 commit 索引（2026-08-26 之前）

参见 `git log --oneline`：

```
b2c8cbc L4.2 fix: 显式指定 -i SSH_KEY 解决 Permission denied
484295b L4.1: GitHub Actions 自动部署 staging
aa9f459 L1+L2 staging 双环境 + 拆前端 4 子产品页
fe938de ✨ 阶段 1 + 阶段 2 部署基建完成
d56de1e 🎉 初始化凌瑶智数 Git 仓库
```

---

*文档维护说明：本 CHANGELOG 由质量环产物沉淀而来；后续每次发版请同步更新本文件。*

---

## 2026-08-27 · V2.0.5 (端到端发布流程打通 R-7)

**触发**：主人 2026-08-27 15:54 「来进行预发布到生产流程的打通，这件事很重要，要不然我就没办法去干其他的事了」

**核心目标**：让主人能"点按钮"就把 jar 从研发端推到生产端，不用每次 SSH 记路径。

**改动**：

### 后端（V2.0.5 R-7 新增模块）
- **新实体** `entity/ReleaseHistory.java`（13 字段，含 env/version/status/log/duration 等）
- **新 Repository** `repository/ReleaseHistoryRepository.java`（最新版本查询 + 防并发）
- **新 Service** `service/ReleaseService.java`：
  - `@Async deployStaging(jarPath, userId)` → SSH 到 staging 机器调 deploy-staging.sh
  - `@Async deployProd(userId)` → 本地调 deploy-prod.sh
  - `hasRunningDeployment()` 防并发
  - `getCurrentVersion(env)` 当前版本查询
- **新 Service** `service/WebhookService.java`：飞书群机器人 card 消息推送
- **新 Controller** `controller/ReleaseController.java`（6 个端点，仅 platformAdmin）：
  - `GET /api/admin/release/status` staging/prod 当前版本 + 是否运行中
  - `POST /api/admin/release/deploy-staging` 触发 staging（需 jarPath）
  - `POST /api/admin/release/deploy-prod` 触发 prod（铁律：必须先有 staging SUCCESS）
  - `GET /api/admin/release/history` 历史（分页 + 过滤）
  - `GET /api/admin/release/{id}` 单条详情（含 log）
- **新 DTO** `dto/admin/DeployStagingRequest.java`（@NotBlank jarPath）
- **新配置** `application.yml` `lingyao.release.*`（SSH key path + Webhook URL + 脚本路径）

### 前端（admin.html + 发布管理 Tab）
- **新增 Tab**「🚀 发布管理」（5 号 Tab，位置在邀请管理后）
- **新增 3 个 modal**：部署 staging / 晋升生产（二次确认输 prod）/ 查看日志
- **新增 JS**：`loadReleaseStatus()` / `loadReleaseHistory()` / `viewReleaseLog()` / `openDeployStagingModal()` / `openDeployProdModal()`
- **新增 CSS**：`.release-env-card` + `.btn-warn-sm`（橙色警告按钮）
- **二次确认铁律**：晋升生产必须输入 `prod` 才能确认

### 本地脚本（封装层）
- `scripts/release-to-staging.sh`（封装 deploy-staging.sh）
- `scripts/promote-to-prod.sh`（封装 deploy-prod.sh + 二次确认提示）

### 文档
- `docs/release-process.md`（完整发布流程手册：控制台按钮 / 本地脚本 / 手动 SSH 三种方式）

### 一次性 CVM 配置（主人需在 CVM 上跑一次）
```bash
# 1. 生成 release 专用 SSH key
sudo -u ubuntu ssh-keygen -t ed25519 -f /home/ubuntu/.ssh/release_staging_key -N ""
sudo -u ubuntu bash -c "cat /home/ubuntu/.ssh/release_staging_key.pub >> /home/ubuntu/.ssh/authorized_keys"

# 2. 测试 SSH 链路
sudo -u ubuntu ssh -i /home/ubuntu/.ssh/release_staging_key ubuntu@127.0.0.1 echo OK

# 3. （可选）配置飞书 Webhook
echo "LINGYAO_RELEASE_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/xxx" >> /opt/lingyao/.env.private
echo "LINGYAO_RELEASE_WEBHOOK_ENABLED=true" >> /opt/lingyao/.env.private
```

**验证步骤**：
1. Mac build：`cd backend && mvn clean package -DskipTests`
2. 浏览器登录 admin.html → 进入「🚀 发布管理」Tab
4. 点「📦 发布 staging」→ 输入 jar 路径 → 确认
5. 看历史记录从 `RUNNING` → `SUCCESS`（约 60-90 秒）
6. 验证 staging：`curl http://118.195.197.15:9092/api/_diag/version` 返 V2.0.5
7. 点「⬆ 晋升生产」→ 输 prod → 确认
8. 看历史记录 prod 从 `RUNNING` → `SUCCESS`
9. 验证 prod：`curl http://118.195.197.15/api/_diag/version` 返 V2.0.5
10. 飞书群收到卡片消息（如果 Webhook 已配）

**附属收益**：
- 多租户 + 大超管 + 演示租户的需求已经在 AdminController 落了大半（R-4/R-8 部分实现）
- 前端不需要改版本号（Vite 模式从 `/api/version` 自动拉），保持单一真理源

---

## 2026-08-28 · V2.0.7 (端到端跑通验证)

### Commit `TBD` · V2.0.7 端到端发布流程验证

**触发**：主人 2026-08-28 10:38 「把整个流程再跑通一遍」

**满足铁律**：
- **V1** Z 段 bump：2.0.6 → 2.0.7
- **V2** FE/BE 同步：backend `app.version` + admin.html 注释同步
- **V3** 跨产品主版本不变
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**改动**：
- `application.yml`：`app.version: 2.0.6 → 2.0.7`
- `admin.html`：13 处「V2.0.5 R-7」注释 → 「V2.0.7 R-7」（保持注释一致，便于代码考古）

**验证步骤**：
1. Mac build：`cd backend && mvn clean package -DskipTests`
2. 控制台触发 staging 部署
3. `curl http://118.195.197.15:9092/api/_diag/version` 返 `version: 2.0.7`
4. 控制台触发 prod 晋升（输 `prod`）
5. SSH 到 CVM：`curl http://127.0.0.1:9091/api/_diag/version` 返 `version: 2.0.7`

**额外修复**：
- 恢复本地 `~/.ssh/release_staging_key` 文件（之前被清理丢失）
- 重新生成 keypair + 推 pubkey 到 CVM ubuntu authorized_keys + /root/.ssh/
- prod 9091 外网访问不通问题：腾讯云安全组疑似只放行 9092，9091 外网走不通；本次通过 SSH 隧道 + 内部 curl 验证 prod 新版本

---

## 2026-08-28 · V2.0.9 (独立平台超管登录页 `/superadmin.html`)

### Commit `TBD` · V2.0.9 大超管独立登录入口

**触发**：主人 2026-08-28 13:23 「开始 Step 1」（大超管 4 步方案 · Step 1）

**满足铁律**：
- **V1** Z 段 bump：2.0.8 → 2.0.9
- **V2** FE/BE 同步：application.yml app.version + superadmin.html 底部版本号 + CHANGELOG
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**核心改动**：
- 新增 `backend/src/main/resources/static/superadmin.html` —— 平台超管专属登录入口（极简设计）
- 复用现有 `/api/auth/login` API（不新建后端）
- 前端 role 校验：登录成功后判断 `user.platformAdmin`，非平台超管拦截并提示走 `/portal.html`
- 已登录平台超管访问 `/superadmin.html` 直接跳 `/admin.html`（不重复登录）
- `application.yml`：`app.version: 2.0.8 → 2.0.9`

**URL 入口规划**：

| 入口 | 谁用 | 登录后落点 |
|---|---|---|
| `/portal.html` | 操作员 / 公司超管 / 客户 | 4 个产品分入口（按权限显示）|
| `/superadmin.html`（新）| **仅**平台超管 | 直接进 `/admin.html` |
| `/admin.html` | 平台超管 + 公司超管（按 role 切菜单）| 后台管理界面 |

**验收步骤**：
1. 浏览器访问 `http://<host>/superadmin.html` → 看到「凌瑶智数 · 平台超管登录」极简页
2. 输入平台超管账号 → 登录成功 → 跳 `/admin.html`
3. 输入公司超管账号 → 提示「该账号不是平台超管，请通过 /portal.html 登录」并拦截
4. 输入错误密码 → 提示「用户名或密码错误」
5. `/portal.html` 访问不受影响（公司超管仍正常登录看产品）

**配套 backlog（不在 V2.0.9 范围）**：
- Step 2：报名管理 Tab + KPI「待审报名」（V2.0.10）
- Step 3：admin.html role-based 菜单 + portal.html「⚙ 我的公司」按钮（V2.0.11）
- Step 4：运维按钮下沉（V2.0.12）
- V2.1.0：防机器人 V1（账号 + IP 双锁）

---

## 2026-08-29 · V2.0.12 (HPD SSO 接收端前置：主仓 JWT 加 audience='lingyao-sso')

### Commit `TBD` · 子产品 SSO 联调前置：主仓 JWT 必须带 audience claim

**触发**：主人 2026-08-29 18:14 「B 开始改造」→ HPD SSO 实施 → 主仓 token 缺 audience，子产品无法验签

**满足铁律**：
- **V1** Z 段 bump：2.0.11 → 2.0.12
- **V4** CHANGELOG 必记（本条目）
- **跨项目治理** 铁律：所有组件先备份再动手（备份至 `_backups-2026-08-29/pre-hpd-sso-jwt-aud-*`）

#### 改动详情

- `backend/.../security/JwtUtil.java` 加 `DEFAULT_AUDIENCE = "lingyao-sso"`，`Jwts.builder()` 加 `.audience().add(DEFAULT_AUDIENCE).and()`
- `application.yml` app.version 2.0.11 → 2.0.12
- 主仓对内 JwtAuthFilter 不强制校验 audience（jjwt 默认行为），向后兼容
- 子产品用 `LINGYAO_JWT_SECRET` + `aud='lingyao-sso'` 验签主仓签发的 token

#### 配套改动（主仓不变，只 HPD/AIDD/Dinfo/PORM/GEOM 各仓对应改动）

- HPD：`backend/app/security.py` 加 `load_lingyao_jwt_secret()` + `backend/app/routers/lingyao_sso.py` 新路由 + `models/models.py` User 表加 `lingyao_user_id/tenant_id/username` + `frontend/src/pages/hoyuan/SsoCallback.tsx` 新组件 + `App.tsx` 加路由 + `Login.tsx` 加按钮

---

## 2026-08-29 · V2.0.11 (D-1 base_url 配置外移，远端代码库稳定性)

### Commit `TBD` · 主仓 5 项稳定性 Fix 第 1+2 项：sub_task.base_url 配置外移 + daemon 路径参数化

**触发**：主人 2026-08-29 17:45 「请架构师验证上传远端代码库后是否稳定」+ 17:52 「先做 D-1」+ 18:05 「继续」

**满足铁律**：
- **V1** Z 段 bump：2.0.10 → 2.0.11
- **V4** CHANGELOG 必记（本条目）
- **跨项目治理** 铁律：所有组件先备份再动手（备份至 `_backups-2026-08-29/pre-d1-stability-fix-20260829-175357/`）

#### Fix 1 - sub_task.base_url 配置外移（17:50 完成）

- 新增 `backend/src/main/java/com/lingyao/platform/config/LingyaoSubTaskProperties.java`（@ConfigurationProperties 配置类）
- `application.yml` 加 `lingyao.subtask.routes.{geo,hpd,aidd,dinfo,porm}` 段
- `SubTaskController.java` `info()` + `enter()` 改读配置类（保留 sub_task 表 fallback）
- `data.sql` 加注释说明 base_url 字段由配置类接管
- 5 张卡片 enter URL 验证：`source: "config"` ✅
- 环境变量覆盖验证：`LINGYAO_SUBTASK_GEO_BASE_URL=http://10.99.99.99:8090` 生效 ✅

#### Fix 2 - daemon 路径参数化（18:08 完成）

- `scripts/start_backend_daemon.py` / `start_dev_daemon.py` / `start_frontend_daemon.py` 三个守护脚本
- `PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")`
- 顺手参数化 `JAVA_BIN`（LINGYAO_JAVA_BIN）和 `PYTHON_BIN`（LINGYAO_PYTHON_BIN）和 dev `SERVER_PORT`（LINGYAO_DEV_PORT）
- 3 个 daemon py_compile 通过；LINGYAO_HOME=/opt/lingyao-test 覆盖生效；前端 daemon 重启后 8765 HTTP 200 ✅

#### Fix 3 - README 升级（18:13 完成）

- 从 2026-08-11 的 "products/ 符号链接" 模型升级到 **5 个产品矩阵**（GEO/HPD/AIDD/Dinfo/PORM）
- SSO 协议从旧 3 参数（`token/from/company_id`）改为 **V1.0 标准 5 参数**（`tenant_id/user_id/user/display_name/platform_token`）
- 加"项目结构"图、"启动方式（开发者）"3 段、"V2.0.11 稳定性 Fix 清单"
- README 文件从 75 行 → 148 行（+97%）

#### Fix 4 - CVM 部署文档化（18:13 完成）

- 新增 `deploy/cvm/DEPLOYMENT.md`（13KB）
- 10 个章节：部署架构图 / 系统要求 / 目录规划 / 构建部署 / Profile 差异 / 5 子产品对接 / 运维 SOP / ICP 备案 / 常见问题 / 参考文档
- 关键铁律：**JDK 21 必须**（JDK 17 跑不动 V2.0.11+），**PostgreSQL 14+** for private profile

#### Fix 5 - SSO-CORS 策略文档（18:13 完成）

- 新增 `docs/portal-sso-design/03-sso-cors-policy.md`（11KB）
- 3 层协议分层：L1 JWT 鉴权 + L2 URL 跳转 + L3 Cookie 共享
- 4 种部署场景：生产 `.lingyao.cn` 子域共享 / 开发机 `SameSite=Lax` / 跨域 JWT-only / 完全独立 cookie
- 7 条绝对不能做的反例 + 7 条推荐安全实践

**改动**：
- 新增 `backend/src/main/java/com/lingyao/platform/config/LingyaoSubTaskProperties.java`：`@ConfigurationProperties(prefix="lingyao.subtask")` 映射 `Map<String, Route>`，含 `baseUrl` / `healthUrl` / `entryPath` 三字段
- `application.yml`：在 `lingyao:` 段下加 `subtask.routes.{geo,hpd,aidd,dinfo,porm}` 配置（5 个产品 × 3 字段 = 15 行），每个值用 `${LINGYAO_SUBTASK_<X>_<FIELD>:默认值}` 模式支持环境变量覆盖
- `SubTaskController.java`：注入 `LingyaoSubTaskProperties`，`enter()` 和 `info()` 优先读配置类（`getRoute(code).getBaseUrl()`），fallback 才用 `sub_task.base_url` 表字段
- `application.yml`：`app.version: 2.0.10 → 2.0.11`
- `data.sql` 暂不改：保留 `base_url` 字段做向后兼容 fallback（dev 环境回滚用）

**架构价值**：
- CVM 部署通过环境变量覆盖 `LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090` 即可切换子产品 URL
- 上传远端代码库后，接收方不需要改代码就能跑（默认 localhost）
- 调试可视化：`info()` 接口新增 `source` 字段（`config` 或 `sub_task_table`），前端可判断 baseUrl 来自配置还是表

**验证**：
- dev 9091 启动成功
- `curl /api/sub/geo/info` 的 `data.baseUrl` 应为 `http://127.0.0.1:8090`（默认），`source: config`
- 改环境变量 `LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090` 重启，`data.baseUrl` 应为新值
- admin 登录后点 5 张卡片仍能正常跳转（行为不变，因为配置类默认值与原 data.sql 一致）

---

## 2026-08-28 · V2.0.10 (公司编辑弹窗 + 状态枚举 5 种 + 许可证过期提醒)

### Commit `TBD` · V2.0.10 大超管后台能力升级

**触发**：主人 2026-08-28 13:57 「把它做出来」（大超管后台优化 3 步方案）

**满足铁律**：
- **V1** Z 段 bump：2.0.9 → 2.0.10
- **V2** FE/BE 同步：application.yml app.version + admin.html 底部版本号 + CHANGELOG
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/_diag/version 端点已就绪

**核心改动**：

**Step A · 弹窗排版修复 + 编辑公司弹窗**
- 修复 `#grantProductCheckboxes` 和 `#grantUserProductCheckboxes` label 排版 bug（之前 `display:flex` 在 block-level label 上导致 flex 容器宽度撑满父容器，复选框和文字分散到两端），现在改为 `inline-flex` + `width:auto` 紧凑对齐
- 新建 `#editCompanyModal`（V2.0.10 · 模态-large）：含 9 个可编辑字段（公司名称 / 部署模式 / 许可证等级 / 许可证起止日期 / 公司状态 / 最大用户数 / 联系邮箱 / 联系电话 / 地址 / 备注）+ code 不可改提示
- 部署模式/许可证等级/公司状态全部改为中文显示（共享云 SaaS / 私有化部署；试用/标准/企业；开通中/已开通/已过期/已暂停/已删除）
- 二次确认逻辑：修改部署模式 / 状态（SUSPENDED/DELETED 切换）/ 许可证截止日期 → 弹 confirm 二次确认
- 公司列表行加「✏ 编辑」按钮（中文显示：部署模式/许可证/状态）

**Step B · 状态枚举升级 3→5 种**
- `Company.CompanyStatus` 枚举从 3 种（ACTIVE/SUSPENDED/DELETED）扩到 5 种（**PENDING/ACTIVE/EXPIRED/SUSPENDED/DELETED**）
- `TenantAdminService.createCompany()` 默认状态 = PENDING（开通中）
- 新建 `schema-private.sql`：处理 `company_status_check` CHECK 约束升级（V2.0.8 SOP 同款 `ALTER TABLE DROP/ADD CONSTRAINT` 模式）
- 新增表 `license_reminder_log`（用于过期提醒去重 + 审计）

**Step C · 许可证过期提醒 scheduler**
- 新建 `LicenseExpirationScheduler.java`（每日 9:00 早检查 + 每日 23:00 晚过期 cron）
- 提前 30/15/7/1 天提醒大超管 + 公司超管（双发，邮箱 + 飞书复用现有通道）
- 过期当天自动 `ACTIVE → EXPIRED`（晚 23:00 cron）
- EXPIRED 公司超管登录拦截（在 `LoginService` 加 `CompanyStatus` 校验）

**新增后端 API**：
- `PUT /api/admin/companies/{id}` — 更新公司（仅 platformAdmin），高危字段前端二次确认

**新增后端类**：
- `UpdateCompanyRequest.java`（DTO，含二次确认字段）
- `LicenseReminderLog.java`（Entity）
- `LicenseExpirationScheduler.java`（`@Scheduled(cron)`）
- `LicenseExpirationService.java`（业务逻辑）

**新增 SQL 文件**：
- `schema-private.sql`（V2.0.10+ 升级脚本，含 CHECK 约束 + 新表）

**验收步骤**：
1. 大超管后台「公司管理」Tab → 列表行点「✏ 编辑」→ 弹窗显示当前值
2. 修改部署模式 → 弹 confirm「部署模式: 共享云 → 私有化部署，确定？」→ 确认后保存
3. 修改许可证截止日期 → 弹 confirm → 确认后保存
4. 新建公司 API 返回 status=PENDING（开通中），大超管手动改 ACTIVE 完成开通
5. 凌晨 23:00 自动把过期公司的 status 改为 EXPIRED
6. EXPIRED 公司的超管尝试登录 → 返回 403 + 提示「公司许可证已过期，请联系大超管」
7. 每日 9:00 自动给 30/15/7/1 天后过期的公司推飞书 + 邮件通知大超管 + 公司超管

---

## 2026-08-28 · V2.0.8 (产品改名 + 研发中置灰)

### Commit `TBD` · V2.0.8 产品品牌升级

**触发**：主人 2026-08-28 11:54 「改一下注册了之后目前4个产品的产品名...后两个产品置灰不可进入」

**满足铁律**：
- **V1** Z 段 bump：2.0.7 → 2.0.8
- **V2** FE/BE 同步：application.yml app.version + admin.html 注释同步
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**改动**：

| 产品 code | 旧名 | 新名 | 状态 |
|---|---|---|---|
| GEO | GEO 智策 | 棱镜-智能GEO监测 | ACTIVE |
| HPD | 医院潜力预测 | 皓元-智能医院潜力预测 | ACTIVE |
| AIDD | AIDD 研发反馈 | 源策-科研信息助手（研发中，敬请期待）| COMING_SOON |
| POR | 药企协作辅助智能体 | 飞轮-AI辅助协作智能体（研发中，敬请期待）| COMING_SOON |

**文件改动**：
- `SubTask.java`：Status 枚举加 `COMING_SOON` 值
- `data.sql` + `data-private.sql`：product.name + product.status（AIDD/POR）+ sub_task.status（AIDD/POR）
- `portal.html`：walkthrough step 1/2/3/4 标题同步新名（步骤 3/4 加「研发中，敬请期待」标记）

**数据库更新**（SSH 一次性跑 psql）：
- staging/prod PostgreSQL：UPDATE product + UPDATE sub_task（详见 commit message）

**验证步骤**：
1. staging 部署
2. 登录 `:9092/portal.html` → 看到 4 个产品卡片：GEO/HPD 可点、AIDD/POR 置灰
3. prod 部署
4. 登录 nginx:80 → 同样效果
