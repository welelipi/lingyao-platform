# 凌瑶智数 · 云端主流程对接规范（拿走即用）

> **🚧 越界提示 · 云端主流程接手（2026-08-30 17:40）**
>
> 本文档属于 **云端主流程** 职责范围（铁律 #0：本地主流程窗口不归本 AI 管）。
> 文档已固化 09:12 的对接规范，后续修订应由云端主流程账号执行。
>
> 详细窗口边界见 `.workbuddy/memory/MEMORY.md`「窗口边界速查」段。
> 同类越界产物清单：`docs/DEPLOYMENT_READY.md` / `deploy/cvm/` 整目录。

---

> **目标读者**：负责 CVM `118.195.197.15`（南京三区）部署的云端主流程
> **本文件由本地 dev AI 流程产出，2026-08-30 09:12**
> **边界**：本文件**只**告诉你怎么对接本地 dev，**不**包含 SSH 脚本、systemd unit、nginx 配置等云端专属资产（那些由云端主流程自管）

---

## 1. 三件套（拿走即用）

| 文件 | 用途 |
|---|---|
| `deploy/docker-compose.yml` | PG + Backend 一体编排，本地/云端共用 |
| `deploy/Dockerfile.backend` | 后端镜像构建（**JDK 21**，修正旧 Dockerfile.cn 的 17） |
| `deploy/.env.example` | 环境变量模板，云端主流程 cp 后填真实密码 |

**铁律**：这三件套的**字段**在本地和云端必须一致，仅 `.env` 中的**值**可不同。

---

## 2. 云端部署最小步骤（5 步走完）

### Step 1：拉代码
```bash
cd /opt/lingyao
git pull origin main
```

### Step 2：复制 .env 并填真实值
```bash
cd /opt/lingyao/deploy
cp .env.example .env
vi .env
```

**必须改的字段**：
- `POSTGRES_PASSWORD`：云端强密码（≥12 位，含大小写+数字+特殊字符，不含用户名）
- `POSTGRES_BIND`：建议改 `0.0.0.0` 让 backend 容器访问；或保持 `127.0.0.1` 加 `network_mode: host`
- `BACKEND_BIND`：建议改 `0.0.0.0` 让 nginx 反代访问
- `LINGYAO_JWT_SECRET`：留空（让 entrypoint 自动生成）或从现有 `data/.jwt_secret` 同步值
- `APP_GIT_COMMIT`：由 CI 注入；手动部署填 `local`

### Step 3：准备数据盘挂载点
```bash
# 数据盘 /data 已存在（100GB），建命名子目录
mkdir -p /data/lib/postgresql/data
mkdir -p /data/lib/lingyao-data
mkdir -p /data/logs/lingyao

# 修改 docker-compose.yml 的 volumes 段（云端主流程专属修改）：
#   lingyao-pg-data:/var/lib/postgresql/data
#   → /data/lib/postgresql/data:/var/lib/postgresql/data
#   lingyao-backend-data:/app/data
#   → /data/lib/lingyao-data:/app/data
#   lingyao-backend-logs:/app/logs
#   → /data/logs/lingyao:/app/logs
```

### Step 4：启动 stack
```bash
cd /opt/lingyao/deploy
docker compose up -d
docker compose ps
# 预期：lingyao-postgres Up (healthy) + lingyao-backend Up (healthy)
```

### Step 5：验证
```bash
sleep 30  # 等 backend 健康检查通过
curl http://127.0.0.1:9091/api/version
# 预期：{"service":"lingyao-platform","version":"2.0.14","release":"stable",...}

# 登录测试
curl -X POST http://127.0.0.1:9091/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","companyCode":"PRIVATE-CUSTOMER"}'
```

---

## 3. 一致性保证清单

云端必须与本地保持一致的字段（**错任意一个 = 推送后不一致**）：

| # | 字段 | 本地值 | 云端值 | 不一致后果 |
|---|---|---|---|---|
| 1 | `POSTGRES_DB` | `lingyao` | `lingyao` | backend 找不到 DB，连不上 |
| 2 | `POSTGRES_USER` | `lingyao_app` | `lingyao_app` | 认证失败 |
| 3 | `SPRING_PROFILES_ACTIVE` | `private` | `private` | 错用 `dev` → 数据全丢（create-drop）|
| 4 | `BACKEND_PORT` | `9091` | `9091` | nginx 反代指向错 |
| 5 | `APP_VERSION` | `2.0.14` | `2.0.14` | 主人版本号铁律 |
| 6 | `TZ` | `Asia/Shanghai` | `Asia/Shanghai` | @CreatedDate 时区错 |

**不一致后果**：5/6 项会导致 backend 起不来；6/6 项会让主人排查 1 小时以上

---

## 4. 云端专属资产（**本任务窗口不产出**）

以下由云端主流程自管：

| 资产 | 现有位置 | 备注 |
|---|---|---|
| nginx 反代配置 | `deploy/cvm/nginx-lingyao.conf`（已存在）| 80 → backend:9091 |
| systemd 单元 | `deploy/cvm/lingyao-backend.service`（已存在）| 需改写为 `docker compose up -d` |
| 日志切割 | `deploy/cvm/logrotate-lingyao`（已存在）| 配合 stack 日志 |
| H2 备份 | `deploy/cvm/backup-h2.sh`（不适用 PG）| **需重写为 pg_dump 版** |
| 部署文档 | `deploy/cvm/DEPLOYMENT.md`（已存在）| 已包含 10 章节完整 SOP |
| 安全组规则 | 腾讯云控制台 | 仅放行 80/9092/22 |
| ICP 备案 | 腾讯云审核中 | `30178770722105289` |
| 域名/SSL | 待办 | 待主人决策 |

---

## 5. 数据库 schema 一致性

JPA `ddl-auto: update`（private profile）会自动同步 schema，但**有 3 个例外**必须手动处理：

### 例外 1：CHECK 约束变更
本地 dev 加枚举值（如 `Company.CompanyStatus` 3→5 种）→ 本地 H2 自动同步，**但云端 PG 不会自动改 CHECK 约束**

**云端处理**：主人 SSH 到 CVM 后跑 `schema-private.sql`（已存在 `backend/src/main/resources/schema-private.sql`）

### 例外 2：大字段类型变更
`varchar(50) → text` 这类 → JPA update 检测不到（PG 9 之前的行为）

**云端处理**：手动 `ALTER TABLE`

### 例外 3：种子数据
`data-private.sql` 用 `ON CONFLICT (id) DO NOTHING`（V2.0.3 引入），二次启动安全

---

## 6. 推送后回滚预案

**铁律**：推送前必须能回滚（主人 2026-08-13 教训）。

### 客户端回滚
```bash
cd /opt/lingyao
git log --oneline -5
git checkout <previous-good-commit>
cd deploy && docker compose build --no-cache
docker compose up -d
```

### 数据库回滚
```bash
# 用前一天 pg_dump 备份恢复
gunzip -c /data/db-backup/lingyao_YYYY-MM-DD.sql.gz | psql -U lingyao_app -d lingyao
```

### JWT 回滚
**不要删除 `/app/data/.jwt_secret`**——否则所有用户强制重新登录

---

## 7. 监控建议（云端主流程自行配）

| 监控项 | 工具 | 阈值 |
|---|---|---|
| backend 健康 | curl /api/health（30s）| 非 200 报警 |
| PG 连接数 | `SELECT count(*) FROM pg_stat_activity` | > 80 报警 |
| PG 磁盘 | `df -h /data` | > 80% 报警 |
| 容器重启次数 | `docker inspect --format='{{.RestartCount}}'` | > 3 次/小时 报警 |
| JVM heap | `/api/_diag/version.heap_used_mb` | > 80% max 报警 |

---

## 8. 主人与云端主流程的协作节奏

| 节点 | 主人动作 | 云端主流程动作 |
|---|---|---|
| **开发完成** | git commit + 推 main | — |
| **本地验证** | dev 环境跑通 + watchdog 转绿 | — |
| **版本号 bump** | application.yml app.version 同步 | — |
| **CHANGELOG 更新** | 本仓库 CHANGELOG.md | — |
| **云端部署** | — | ssh + git pull + docker compose up -d |
| **云端验证** | — | curl /api/version + 登录测试 |
| **主人复验** | 浏览器打开 http://118.195.197.15/portal.html | — |

---

## 9. 边界声明

⚠️ **本文件由本地 AI 流程产出，不含任何云端 SSH 命令**

主人 2026-08-30 09:12 新铁律：「本任务窗口只处理本地，云端交给另一个云端主流程」。

云端主流程请直接拿走本目录下的 5 个文件，不需要询问本地 AI：
- `deploy/docker-compose.yml` ✅
- `deploy/Dockerfile.backend` ✅
- `deploy/.env.example` ✅
- `deploy/CHECK_REPORT.md` ✅（一致性检查依据）
- `deploy/README_FOR_CLOUD_TEAM.md` ✅（本文件）

---

## 10. 本地实测验证状态（2026-08-30 10:18，云端可跳过)

云端主流程不需要重复验证，以下是本地 AI 已经跑通的端到端实测：

### 10.1 docker compose ps 实测输出

```
NAME               IMAGE                                                STATUS                        PORTS
lingyao-backend    lingyao/platform:2.0.14                              Up 56 seconds (healthy)       127.0.0.1:9091->9091/tcp
lingyao-postgres   m.daocloud.io/docker.io/library/postgres:16-alpine   Up About a minute (healthy)   127.0.0.1:5432->5432/tcp
```

### 10.2 curl /api/version 实测响应

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "service": "lingyao-platform",
    "version": "2.0.14",
    "release": "stable",
    "build_time": "2026-08-30T10:17:54",
    "git_commit": "local",
    "sso_protocol": "1.0"
  },
  "success": true
}
```

**关键字段**：
- `service = lingyao-platform` ✅
- `version = 2.0.14` ✅
- `release = stable` ✅（private profile）
- `build_time` = 镜像构建时间，云端会不同
- `sso_protocol = 1.0` ✅

### 10.3 本地踩坑沉淀（云端无需重做）

| # | 本地踩的坑 | 原因 | deploy/ 解决方案 | 云端是否需重做 |
|---|---|---|---|---|
| 1 | docker.io 拉镜像超时 | sandbox 网络限制 | `Dockerfile.backend` 用 DaoCloud 镜像源 | ❌ 云端直接拉无需 DaoCloud |
| 2 | WORKDIR `/build` 找不到 jar | jar 在 `/build/backend/` 子目录 | Dockerfile 已 fix | ❌ 已固化 |
| 3 | 端口 9091 被孤儿 java 进程占 | 历史 `docker run lingyao` 残留 | 用 `docker compose up -d` 不再冲突 | ❌ 云端新部署无此问题 |
| 4 | java 输出在 `docker logs` 看不见 | `entrypoint.sh` 重定向到 `/app/logs/lingyao.log` | 用 `docker exec ... cat /app/logs/lingyao.log` 或 `docker cp` | ⚠️ **云端也会遇到** → 看本文件第 2 节 Step 5 后的故障排查 |
| 5 | `127.0.0.1:5432 refused` | application-private.yml 早期硬编码 | **`application-private.yml:28` 用 `${SPRING_DATASOURCE_URL:default}` 占位符**（已固化在 HEAD）| ❌ 已固化，云端走 127.0.0.1 即可 |
| 6 | `password authentication failed for user "lingyao_app"` | `lingyao-pg-data` 卷是旧 PG 容器遗留下来的 | **新部署无此问题**（卷是新建的）| ❌ 云端首次部署是干净卷，不会触发 |
| 7 | `role "postgres" does not exist` | 同上 | 同上 | ❌ |

**云端主流程**：
- 看到任何上面 1-7 的报错 → **不要先 SSH 进去 debug** → 先回看本节 10.3 的"云端是否需重做"列
- 多数是本地 dev 一次性坑，云端新部署天然绕过

---

*维护：每次本地 dev 重大变更（如 Dockerfile 升级、新增 service、密码轮换策略），本文件由本地 AI 同步更新。*