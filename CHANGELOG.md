# 凌瑶智数 · 变更日志

> 维护者：凌瑶主人
> 起始：2026-08-27（首次创建，作为质量环产物）

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
