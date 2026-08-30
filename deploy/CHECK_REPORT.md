# 两端一致性 Check 报告

> **维护者**：凌瑶主仓 AI 流程（本地 dev 环境）
> **生成时间**：2026-08-30 09:12
> **触发**：主人下达「保证本地健康稳定 + 保证两端一致」
> **范围**：本报告只 check 本地 dev 环境与云端 prod 预期的一致性，**不**做云端部署

---

## 0. 一句话结论

**本地 dev 当前 7/12 项一致，5 项存在不同程度漂移**，最致命的是 **JDK 版本（17 vs 21）** 和 **PG 容器化（无）**。本报告附完整修复方案。

---

## 1. 一致性 Check 表（12 项）

| # | 维度 | 本地 dev 现状 | 云端 prod 预期 | 状态 | 修复动作 |
|---|---|---|---|---|---|
| 1 | **Spring Boot jar** | `backend/target/lingyao-platform.jar`（JDK 21 编译）| 同 jar，JDK 21 编译 | ✅ **一致** | — |
| 2 | **application.yml 默认 profile** | `dev`（H2 + create-drop）| `private`（PG + update）| ⚠️ **profile 不同** | ✅ 容器内用 `private`，已通过 `SPRING_PROFILES_ACTIVE` 覆盖 |
| 3 | **application-private.yml** | 文件存在，PG 配置齐全 | 文件随 jar 一起推到云端 | ✅ **一致** | — |
| 4 | **JDK 版本** | `backend/docker/Dockerfile.cn` **用 17** | CVM 必须 **JDK 21**（V2.0.11+ 铁律）| 🔴 **致命漂移** | ✅ **新建 `deploy/Dockerfile.backend` 用 21** |
| 5 | **PostgreSQL 数据库** | 本地 Docker 容器 `lingyao-postgres`（16-alpine，DB=lingyao）| 云端同样 PG 16+ | ✅ **架构一致** | — |
| 6 | **PG 用户/密码** | `lingyao_app / Lingyao_Prod_2026_Pg!` | 云端必须改密 | ⚠️ **值需替换** | ✅ 通过 `.env` 注入，云端主流程生成 |
| 7 | **LINGYAO_JWT_SECRET** | `data/.jwt_secret`（64B base64，0600）| 云端必须独立密钥 | ⚠️ **值需替换** | ✅ 通过 `.env` 注入；或留空让 entrypoint 生成 |
| 8 | **DDL 策略** | dev `create-drop`，prod `update` | prod 用 `update`（保留数据）| ✅ **profile 内已分离** | — |
| 9 | **数据初始化 SQL** | dev `data.sql`（多公司），prod `data-private.sql`（1 公司 + 1 超管）| prod 用 `data-private.sql` | ✅ **profile 内已分离** | — |
| 10 | **JVM 参数** | `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC` | 同 | ✅ **一致** | — |
| 11 | **时区** | `TZ=Asia/Shanghai` | 同 | ✅ **一致** | — |
| 12 | **健康检查端点** | `/api/health`（GET，私有不公开）| 同 | ✅ **一致** | — |

**统计**：✅ 一致 7 项 / ⚠️ 需注意 3 项（profile 切换 + 密码 + JWT） / 🔴 致命漂移 1 项（已修） / 🔴 缺失 1 项（PG 容器化，已补）

---

## 2. 致命问题详情

### 🔴 问题 1：JDK 版本漂移（已修）

**文件**：`backend/docker/Dockerfile.cn`

**现状**：
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder  # ❌ 17
FROM eclipse-temurin:17-jre                    # ❌ 17
```

**云端预期**：JDK 21（`openjdk-21-jdk-headless`，V2.0.11+ pom.xml `<java.version>21</java.version>` 编译要求）

**后果**：用 Dockerfile.cn 在云端构建 → 镜像里 jar 跑起来 JPA 启动报 `Unsupported class file major version 65`（v65 = JDK 21 class），整个应用直接挂

**修复**：新建 `deploy/Dockerfile.backend`，FROM 改 21：
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder  # ✅ 21
FROM eclipse-temurin:21-jre                   # ✅ 21
```
保留原 `backend/docker/Dockerfile.cn` 不动（云端主流程如需旧版可继续用），主流程走新版。

### 🔴 问题 2：PG 容器化缺失（已补）

**现状**：`deploy/` 目录下没有 `docker-compose.yml`，云端部署只能裸跑 PG + 手动启 backend

**修复**：新建 `deploy/docker-compose.yml`，把 PG + Backend 装进同一 stack，命名 volume 持久化数据

### 🔴 问题 3：H2 备份脚本不通用（边界外）

**文件**：`backend/docker/backup.sh` 是 H2 专用

**影响**：H2 文件备份在 PG 场景不适用（应用 PG 的应走 `pg_dump`）

**处置**：**不在本任务窗口处理**（主人新铁律：AI 不写云端脚本）。云端主流程负责：
1. 用 `pg_dump` 重写备份脚本
2. 配 systemd timer 每日凌晨 3 点执行
3. 保留 7 天滚动

---

## 3. 一致性保证的 5 道保险

本任务窗口产出的资产已嵌入以下保险：

### 保险 1：单一 docker-compose.yml 模板
本地 dev 和云端 prod 跑**同一份 yaml**，差异仅由 `.env` 注入 → 主人铁律「避免两边不一致」零漂移

### 保险 2：环境变量严格校验
`docker-compose.yml` 用 `${POSTGRES_PASSWORD:?必须设置...}` 语法 → 主人忘填密码直接启动失败，绝不带错默认值跑

### 保险 3：健康检查双层
- Docker 层 `HEALTHCHECK`（镜像自带）
- Compose 层 `healthcheck.test`（stack 编排）

任一层未绿 → `depends_on.condition: service_healthy` 阻断了 backend 启动顺序

### 保险 4：JWT secret 自动持久化
复用现有 `backend/docker/entrypoint.sh` → 容器重建不丢密钥，跨重启 SSO token 不失效

### 保险 5：时区统一
`TZ=Asia/Shanghai` 镜像层 + `application-private.yml` `hibernate.jdbc.time_zone=Asia/Shanghai` 双层保险 → `@CreatedDate` / `NOW()` 时区零漂移

---

## 4. 本地 dev 验证清单（主人可手动跑）

```bash
# 1. 进入 deploy 目录
cd /Users/hua/Documents/myself/凌瑶/deploy

# 2. 复制环境变量模板
cp .env.example .env

# 3. 编辑 .env，至少改 POSTGRES_PASSWORD
vi .env
# POSTGRES_PASSWORD=LingYao@Dev_2026_Pg!     ← 本地推荐值（仅 dev）
# 其他保持默认

# 4. 启动
docker compose up -d

# 5. 验证
docker compose ps  # 看 postgres Up + backend Up (healthy)
sleep 30 && curl http://127.0.0.1:9091/api/version

# 预期输出：{"service":"lingyao-platform","version":"2.0.14","release":"stable",...}

# 6. 登录测试
curl -X POST http://127.0.0.1:9091/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","companyCode":"PRIVATE-CUSTOMER"}'
# 预期：返 200 + access_token

# 7. 验证后清理（保留容器，看主人需要）
docker compose down  # 不删 volume
```

---

## 5. 给云端主流程的边界声明

⚠️ **本任务窗口不处理云端**（主人 2026-08-30 09:12 铁律）。

云端主流程需自行处理：
- [ ] CVM 安全组放行 80 / 9092 / 22；**不放行 9091 / 5432 公网**
- [ ] nginx 反代 80 → backend:9091（已有 `deploy/cvm/nginx-lingyao.conf`）
- [ ] systemd `lingyao-stack.service`（参考 `deploy/cvm/lingyao-backend.service` 改写）
- [ ] 每日 `pg_dump` 备份 + logrotate
- [ ] 域名 ICP 备案（`30178770722105289` 审核中）
- [ ] 飞书 Webhook（`LINGYAO_RELEASE_WEBHOOK_URL`）

云端主流程拿走 `deploy/docker-compose.yml` + `Dockerfile.backend` + `.env.example` 即可直接落地。

---

## 6. 主人铁律自检清单

- [x] **AI 不主动 push**（主人 2026-08-03 + 08-29 强化）— 本次只本地 commit 待主人 PUSH
- [x] **本地 dev 用 start_dev_daemon** — 主人 MEMORY 铁律
- [x] **JDK 21 铁律** — 已升级 Dockerfile
- [x] **DB 路径单一真理源** — 全部走 application*.yml，无硬编码
- [x] **重启必发前端地址** — 本次未重启服务
- [x] **前后端版本号同步** — APP_VERSION=2.0.14 与 application.yml 同步
- [x] **所有组件先备份再动手** — 仅新建文件，无修改现有代码，无需备份
- [x] **Git Remote 变更必须先报告** — 本次未动 remote
- [x] **本任务窗口只处理本地**（主人 09:12 新铁律）— 已严格遵守

---

## 7. 本地实测验证（2026-08-30 10:18）

### 7.1 端到端实测成功

| # | 验证项 | 实测结果 | 状态 |
|---|---|---|---|
| 1 | `docker compose up -d` | lingyao-postgres + lingyao-backend 都 Started | ✅ |
| 2 | `sleep 15 && docker compose ps` | 两容器都 `(healthy)` | ✅ |
| 3 | `curl http://127.0.0.1:9091/api/version` | HTTP 200 + `version=2.0.14, release=stable` | ✅ |
| 4 | Spring Boot DispatcherServlet | 初始化成功 | ✅ |
| 5 | HikariCP 连 PG | `lingyao-postgres:5432` 解析正确 | ✅ |
| 6 | JPA ddl-auto: update | 表结构同步成功 | ✅ |

### 7.2 curl /api/version 实测响应

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

### 7.3 docker compose ps 实测输出

```
NAME               IMAGE                                                COMMAND                   SERVICE            CREATED              STATUS                        PORTS
lingyao-backend    lingyao/platform:2.0.14                              "/app/entrypoint.sh"      lingyao-backend    11 minutes ago       Up 56 seconds (healthy)       127.0.0.1:9091->9091/tcp
lingyao-postgres   m.daocloud.io/docker.io/library/postgres:16-alpine   "docker-entrypoint.s…"   lingyao-postgres   About a minute ago   Up About a minute (healthy)   127.0.0.1:5432->5432/tcp
```

### 7.4 关键诊断（云端首次部署可能遇到的）

| 症状 | 原因 | 处理 |
|---|---|---|
| 容器反复重启 | HikariCP 连不上 PG | 先看 `/app/logs/lingyao.log`（entrypoint.sh 重定向 java 输出）|
| `password authentication failed` | env 密码与 .env 不一致 | 核对 `docker inspect` 的 `LINGYAO_DB_PASSWORD` 与 `POSTGRES_PASSWORD` 是否一致 |
| `role "postgres" does not exist` | 首次 init 用了非默认 superuser | 这是正常的，不要用 `postgres` 登录，用 `POSTGRES_USER` 配的 superuser |

---

*报告维护：每次主人下达「重新 check 两端一致性」时，本文件追加新一节，避免重头查。*