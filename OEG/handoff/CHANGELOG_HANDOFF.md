# CHANGELOG_HANDOFF.md — 移交日志

## 2026-08-11 14:04 — 旧窗口移交

### 移交人
花卷（WorkBuddy AI，会话 A）

### 接收人
花卷（WorkBuddy AI，会话 B，OEG 任务下新窗口）

### 移交原因
主人原话：「我会废弃掉这个我们现在这个对话窗的出入口，然后我会新建一个呃新建一个任务，把这个任务放在凌瑶的这个项目的文件夹内，然后命名为 OEG，然后继续往前推进。我需要你把你现在所有的记忆，所有的功能工作的这个进度全都打包记录好，然后我告诉另外一个新窗口的时候，让他直接接过来就可以了。」

### 移交内容
1. **目录**：`/Users/hua/Documents/myself/凌瑶/OEG/`（新建，3 个顶层文件 + handoff 子目录）
2. **文档清单**：
   - `START_HERE.md`（7 章，含接管动作清单）
   - `HANDOFF.md`（9 章，详细交接）
   - `PROJECT_STATE.json`（机器可读快照）
   - `handoff/CONTEXT_BRIEF.md`（90 秒 briefing）
   - `handoff/CONTEXT_DUMP.md`（完整上下文）
   - `handoff/RUN_COMMANDS.md`（命令速查）
   - `handoff/CHANGELOG_HANDOFF.md`（本文件）
3. **凌瑶主项目 README 更新**：进度勾选同步 Phase 11 子项。
4. **凌瑶主项目 memory 同步**：OEG 2026-08-11.md 已 copy 到 `凌瑶/.workbuddy/memory/2026-08-11.md`（Phase 1、10、11 全部内容）。

### 当前服务快照
- 凌瑶 portal :8765 PID 37891 ✓
- GEO 后端 :9090 PID 31375/31378/31379 ✓
- GEO 前端 :9180 PID 34049 ✓
- AIDD 前端 :9191 PID 37811 ✓

### 关键文件已写入路径
- `/Users/hua/Documents/myself/凌瑶/OEG/START_HERE.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/HANDOFF.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/PROJECT_STATE.json`
- `/Users/hua/Documents/myself/凌瑶/OEG/handoff/CONTEXT_BRIEF.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/handoff/CONTEXT_DUMP.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/handoff/RUN_COMMANDS.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/handoff/CHANGELOG_HANDOFF.md`
- `/Users/hua/Documents/myself/凌瑶/OEG/next_steps.md`（待新窗口填入）
- `/Users/hua/Documents/myself/凌瑶/README.md`（更新）
- `/Users/hua/Documents/myself/凌瑶/.workbuddy/memory/2026-08-11.md`（同步 OEG memory）

### 主人下一步
- 在 WorkBuddy UI 切换到「凌瑶智数」工作区。
- 在 `凌瑶/OEG/` 下建立任务。
- 把新窗口指给 AI 助手，并提示"先读 `OEG/START_HERE.md`"。

### 旧窗口的告别
- 旧窗口的对话已废弃。
- 任何上下文追加到 `凌瑶/OEG/handoff/CHANGELOG_HANDOFF.md` 即可。
- 旧窗口不要继续推进任何任务。

---

_本文件由 2026-08-11 14:04 旧窗口生成。_

## 2026-08-11 14:14 — 旧窗口收尾追加

### 旧窗口的最终状态
- 4 端口服务全部仍存活（PID 见 PROJECT_STATE.json）。
- 凌瑶主项目 README 已更新（Phase 11 全部勾选）。
- OEG memory 已 copy 到 `凌瑶/.workbuddy/memory/2026-08-11.md`，新窗口可直接在凌瑶工作区读取。
- 凌瑶主项目新增产物：
  - `凌瑶/OEG/`（OEG 任务目录，含 START_HERE / HANDOFF / PROJECT_STATE / handoff/ / next_steps）
  - `凌瑶/README.md`（更新）
  - `凌瑶/.workbuddy/memory/2026-08-11.md`（从 OEG 同步）

### 旧窗口彻底关闭

---

## 2026-08-11 14:25 — 新窗口（凌瑶 · OEG 任务）接管完成

### 接管人
花卷（WorkBuddy AI，会话 B）

### 主人授权动作
1. 读取 `OEG/START_HERE.md` ✅
2. 主人选定方案 **C：B 路径软链化 + 实体重定位**（替换原 OEG 软链接到外部路径 → 物理搬入凌瑶本地）

### Step 1 — 备份 + 停 4 服务 ✅
- 备份 `geo.db` → `凌瑶/OEG/_backup/2026-08-11_pre_relocate/geo.db.snapshot.final`（207 MB，integrity=ok）
- 备份 `凌瑶/portal-backend-py/main.py` → `凌瑶/OEG/_backup/2026-08-11_pre_relocate/main.py.orig`
- 杀掉残留 uvicorn 子进程 PID 31378 / 31379 / 44408 + Node vite + http.server
- 4 端口（8765 / 9090 / 9180 / 9191）全部空闲

### Step 2 — B 路径软链化（portal backend + 文档）✅
- 改动 `凌瑶/portal-backend-py/main.py` 第 24-32 行：
  - 旧：`ROOT_DIR.parent` 硬编码绝对路径 `/OEG/geo-platform/geo-backend-py/data`
  - 新：`ROOT_DIR / "products" / "geo-platform" / "geo-backend-py" / "data"`（基于 `__file__.resolve()`）
- 同改动：`PROJECT_STATE.json`、`HANDOFF.md`、`RUN_COMMANDS.md`、`START_HERE.md`、`CONTEXT_BRIEF.md`、`CONTEXT_DUMP.md` 中所有 `/OEG/` `/AIDD/` 绝对路径 → 凌瑶下相对路径（链接解析）
- AIDD 4 处路径暂保留原 `/AIDD` 目标（暂不搬移 AIDD）

### Step 3 — C 实体重定位 ✅
- `mv /Users/hua/Documents/myself/OEG → /Users/hua/Documents/myself/凌瑶/_external/OEG`（同分区 inode rename，0 秒，8.4 GB 完整迁移，`.git` 完整）
- 删除旧软链接 `products/geo-platform → /Users/hua/Documents/myself/OEG/geo-platform`
- 创建新软链接 `products/geo-platform → /Users/hua/Documents/myself/凌瑶/_external/OEG/geo-platform`
- 软链接解析 + 关键文件可访问性验证全部通过

### Step 4 — 启动 4 服务 + 端到端冒烟 ✅
| 端口 | 服务 | PID | HTTP |
|---|---|---|---|
| 8765 | 凌瑶 portal | 45700 | 200 |
| 9090 | GEO 后端 | 45538/45580/45581 | 200 |
| 9180 | GEO 前端 (Vite) | 45575 | 200 |
| 9191 | AIDD 前端 | 45534 | 200 |

SSO 链路验证：
- 凌瑶登录获取 token（payload iss=lingyao-portal）✅
- 凌瑶 token 进 GEO dashboard API（companyCount=2, userCount=23, 5 role 分布）✅
- 跨公司隔离（cid=1 → 18 条 reports）✅
- AIDD login.html 含 `consumeSsoFromLingYao` SSO 代码（8 处 lingyao 关键字）✅

### Step 5 — git 状态 ✅
- `products/geo-platform` 通过软链 `git status --short` 正常
- 分支：master
- ~50 个 modified files（与 PROJECT_STATE.json 记录一致）

### Step 6 — 端到端冒烟 ✅
- 已在 Step 4 完成。

### Step 7 — 向主人报告 ✅
- "OEG 接管完成，凌瑶自包含化、C 方案实体重定位、4 服务运行健康。当前可推进 P0：候选 A（AIDD 后端 JWT 改造）或候选 E（GEOadmin 密码改回强密码）。" — 见下文"向主人汇报"段。

### 接管动作清单全部完成
- [x] Step 1 — 阅读 HANDOFF.md / PROJECT_STATE.json / CONTEXT_DUMP.md 关键章节 ✅
- [x] Step 2 — 校验 4 端口 ✅
- [x] Step 3 — 读 12 项 SaaS 决策、Phase 10 视觉规范、Phase 11 凌瑶门户化 ✅
- [x] Step 4 — 询问主人下一步 ✅（已选 C 方案）
- [x] Step 5 — git status 对齐 ✅
- [x] Step 6 — 启动端到端冒烟 ✅
- [x] Step 7 — 报告 ✅

### 重要变更摘要
1. **凌瑶自包含化**：原 /OEG 物理位置 → /凌瑶/_external/OEG；凌瑶/products/geo-platform 软链接重新指向凌瑶本地目标。
2. **路径动态化**：凌瑶/portal-backend-py/main.py 不再硬编码绝对路径，通过 ROOT_DIR + 软链解析。
3. **文档同步**：OEG/ 下 8 个文件已无 `/OEG/` 绝对路径残留。
4. **AIDD 暂未搬移**：保持原 `/Users/hua/Documents/myself/AIDD` 目标不动；如需下次搬移，主人示意即可。

### 主人的下一步（候选）
按 `OEG/next_steps.md` 优先级，建议下一轮推进：
1. **候选 E（5 分钟，立刻止血）**：GEOadmin 密码改回强密码
2. **候选 A（1-2 个会话）**：AIDD 后端 JwtAuthInterceptor (Java)，闭环 SSO 故事
3. **候选 B（1 个会话）**：审计日志 + Platform-Hub dashboard 前端
4. **候选 C（2 个会话）**：HTTPS / 数据库备份 / 升级脚本（生产化 P0）

主人示意哪一项，我立刻开干。

---

## 2026-08-11 15:25 新窗口 推进 next_steps A/B/C（花卷）

主人示意「ABC都干」。本轮一次性把候选 A / B / C 全做。

### A：AIDD 后端 JWT 改造（#P0-A，代码完成，端到端待主人启动 AIDD 后端时验证）
- `pom.xml` 加 jjwt 0.12.6（api/impl/jackson 三件套）
- 新建 `com.aidd.copilot.security.JwtTokenProvider`（HS256 解析，凌瑶同 secret）
- 新建 `com.aidd.copilot.security.JwtAuthInterceptor`（从 Authorization 头 → 解析 token → 注入 TenantContext）
- 改 `WebMvcConfig`：注册 JwtAuthInterceptor（order=HIGHEST_PRECEDENCE），TenantInterceptor 在后
- 改 `TenantInterceptor`：preHandle 头部短路（JwtAuthInterceptor 已注入则直接放行）
- `application.yml` 加 `copilot.jwt.secret: ${LINGYAO_JWT_SECRET:}`
- **mvn clean compile 通过**（49 源文件全编译成功）
- **端到端未做**：AIDD 后端未启动，且数据库需预置 tenantCode='1'/'2' 记录（与凌瑶/GEO cid 映射）

### B：审计日志 + Platform-Hub dashboard 前端（#P1-B）
- `api/index.js` 追加 `platformHubApi`（5 端点：dashboard / companies / users / audit-logs / licenses）
- 新建 `pages/PlatformHub.vue`（5 KPI 卡片 + 公司列表 + 用户总览 + 审计入口 + 许可证占位）
- 新建 `pages/AuditLog.vue`（公司审计日志表格 + 过滤：公司 ID / 操作类型 / 条数）
- `router/index.js`：/admin/platform-hub 从 Placeholder.vue 切到 PlatformHub.vue；新增 /audit-log 路由
- **Vite 编译全部通过**（main.js / PlatformHub.vue / AuditLog.vue HTTP 200）
- **端到端验证通过**：凌瑶 SSO token → 5 个 platform-hub API 全部返回真实数据
  - /dashboard → companyCount=2, userCount=23, new_users_last_7d=5
  - /companies → 2 家
  - /users → 23 个（含 companies 数组）
  - /licenses → 2 个（含 features JSON 解析）
  - /audit-logs → 4 条（user.role_change / invitation.accept 等）

### C：HTTPS + 数据库备份 / 升级脚本（#P0-C）
- 新建 `nginx-geo.conf.example`（80→443 强制跳转 + TLS 1.2/1.3 + HSTS + 安全头 + 限速 + SSE 不缓冲）
- 新建 `backup.sh`（WAL checkpoint + sqlite3 .backup + gzip + sha256 + 30 天自动清理 + S3/OSS/COS 上传）
- 新建 `upgrade.sh`（备份 → git pull → schema_migrations 元数据 → 按 NNNN 顺序执行 pending → 重启 → 健康检查）
- 新建 `crontab.example`（每日 02:00 自动备份 + 每周异地同步 + 证书续签监控 + 自愈）
- 新建 `migrations/0001_audit_log_indexes.sql`（加 4 个索引，加速 audit-logs 接口；幂等 IF NOT EXISTS）
- `install-saas.sh` 追加 Step 5（ENABLE_HTTPS=1 时 certbot 自动签发 + Nginx 模板部署）
- `SAAS-DEPLOY.md` 新增第十一/十二/十三/十四章（HTTPS + 备份 + 升级 + 文件清单），版本号 #SAAS-6.1 → #SAAS-6.2
- **bash -n 全部脚本通过**
- **backup.sh 真冒烟通过**：源 207 MB → 压缩后 45 MB（4.6× 压缩），integrity_check=ok，sha256 落地

### 服务状态（4 端口全部健康）
- 8765 凌瑶 portal — PID 49376 — HTTP 200
- 9090 GEO 后端 — PID 48928 — HTTP 200（5 个 platform-hub API 验证通过）
- 9180 GEO 前端 — PID 48958 — Vite v6.4.3 ready（PlatformHub/AuditLog.vue 已编译）
- 9191 AIDD 前端 — PID 48931 — HTTP 200

### 后续待办（主人示意）
- 候选 E（5 分钟）：GEOadmin 密码改回强密码（PROJECT_STATE.json 还残留明文）
- 候选 D（P1）：CACS / 协作智能体模块占位骨架
- A 任务端到端验证：主人启动 AIDD 后端 + 预置 tenantCode='1'/'2' → curl 凌瑶 token → AIDD /api 接受
- 私有化部署验证：ENABLE_HTTPS=1 真实跑一次 install-saas.sh prod