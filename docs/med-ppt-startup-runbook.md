# MED-PPT 启动 Runbook（2026-08-30 实测版）

> **凌瑶空间数据库治理 · 6 步启动 SOP 执行纪要**
>
> 适用：MED-PPT / msl-ma-agent 本地开发环境（macOS + Docker lingyao-postgres）

---

## 🎯 启动拓扑

```
┌─────────────────────────────────────────────────────┐
│  浏览器  →  MED-PPT Frontend (127.0.0.1:19881)      │
│             │ (vite proxy → :19880)                  │
│             ▼                                        │
│  MED-PPT Backend  (127.0.0.1:19880)  ← daemon       │
│             │                                        │
│             ▼                                        │
│  PostgreSQL  (127.0.0.1:5432)                       │
│   ├─ database: lingyao   (owner: lingyao_app)        │
│   └─ database: msl_ma_db (owner: msl_ma)  ← 本次新增 │
└─────────────────────────────────────────────────────┘
```

**端口约定**（已对齐 MED-PPT frontend `vite.config.ts` proxy 默认值）：
- 后端：**19880**（Dockerfile 之前写 8000 是错的，已修）
- 前端：**19881**（`npm run dev`）
- DB：**5432**（复用 lingyao-postgres 容器，不新建容器）

---

## ✅ 6 步启动 SOP（已完成 1-4，待主人验证 5-6）

### ✅ Step 1: 建库 + 建角色（已完成 · 护栏 1）

**在 lingyao-postgres 容器内执行**：

```sql
CREATE USER msl_ma WITH PASSWORD 'msl_ma_pwd';
CREATE DATABASE msl_ma_db OWNER msl_ma;
-- 撤销 PUBLIC 默认 CONNECT（关键隔离）
REVOKE CONNECT ON DATABASE lingyao FROM PUBLIC;
REVOKE CONNECT ON DATABASE msl_ma_db FROM PUBLIC;
REVOKE CREATE ON DATABASE msl_ma_db FROM PUBLIC;
GRANT CONNECT ON DATABASE lingyao TO lingyao_app;
```

**权限矩阵**（已验证）：

| 角色 | 连 lingyao | 连 msl_ma_db |
|---|---|---|
| lingyao_app | ✅ | ✅ |
| msl_ma | ❌（permission denied）| ✅ |

### ✅ Step 2: alembic upgrade head

```bash
cd /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/backend
/Users/hua/.workbuddy/binaries/python/envs/med-ppt/bin/alembic upgrade head
# 预期：4 个迁移依次执行（2026_08_12_1200_initial_schema → ..._draft_files）
```

### ✅ Step 3: FastAPI daemon 启动

```bash
python3 /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/scripts/start_backend_daemon.py
# 后端日志：/Users/hua/Documents/myself/MED-PPT/logs/backend.out
# PID 文件：/tmp/med-ppt-backend.pid
# 健康检查：http://127.0.0.1:19880/health
```

### ✅ Step 4: Vite daemon 启动

```bash
python3 /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/scripts/start_frontend_daemon.py
# 前端日志：/Users/hua/Documents/myself/MED-PPT/logs/frontend.out
# PID 文件：/tmp/med-ppt-frontend.pid
# 访问地址：http://127.0.0.1:19881/
```

### ⏳ Step 5: 验证（主人执行）

```bash
# 5.1 后端 healthcheck
curl http://127.0.0.1:19880/health
# 预期：{"status":"ok"}

# 5.2 前端首页（vite）
curl -I http://127.0.0.1:19881/
# 预期：HTTP/1.1 200 OK

# 5.3 API 端到端（需登录/有 token）
curl http://127.0.0.1:19880/api/v1/health
```

### ⏳ Step 6: 浏览器验证（主人执行）

打开浏览器访问：**http://127.0.0.1:19881/**

---

## 🔐 护栏落地清单

| 护栏 | 状态 | 文件 |
|---|---|---|
| **护栏 1** msl_ma 角色最小权限 | ✅ 已建 | lingyao-postgres 容器内 |
| **护栏 2** backup-pg.sh 多库支持 | ✅ 已改 + 验证 | `凌瑶/deploy/cvm/backup-pg.sh` |
| **护栏 3** lingyao-postgres db 监控 | ⏳ 待独立任务 | MPDM 仓有 SQLite monitor，PG 需另写 |

---

## 📦 本次启动前置条件

1. **Python 3.13.12** + venv `/Users/hua/.workbuddy/binaries/python/envs/med-ppt`
2. **Docker lingyao-postgres** 容器 Up（已 Up 3h+）
3. **msl_ma_db 库 + msl_ma 角色**已建（Step 1）
4. **alembic 迁移**已跑（Step 2）
5. **MED-PPT frontend node_modules** 已装（已存在，免 npm install）

---

## ⚠️ 启动期发现的关键问题（已修）

### 🔴 问题 1: alembic.ini 命名不一致（已修 12:43）

- 旧：`postgresql+psycopg2://pmp:pmp_dev_pwd_2026@127.0.0.1:5432/msl_ma_db`
- 新：`postgresql+psycopg://msl_ma:msl_ma_pwd@localhost:5432/msl_ma_db`

### 🔴 问题 2: psycopg2-binary 缺 wheel（已修 13:05）

- **macOS arm64 + Python 3.13 无 psycopg2-binary==2.9.9 pre-built wheel**
- pip 必须 source build，缺 `pg_config` 可执行文件
- **修复**：升级到 `psycopg[binary]>=3.1.0`（psycopg3，pre-built）
- 同步修改 3 文件：`requirements.txt` + `alembic.ini` + `config.py` + `.env`

### 🔴 问题 3: Dockerfile 端口错误（已修 13:05）

- 旧：`EXPOSE 8000` + `--port 8000`
- 新：`EXPOSE 19880` + `--port 19880`
- 同步修改：`Dockerfile` + `Dockerfile.dev` + `docker-compose.yml` + `start_backend_daemon.py`

### 🟡 问题 4: 8000 端口冲突（绕开方案）

- MPD-platform 项目（`code/MPD-platform`，凌瑶空间外）占用了 8000 端口
- **绝对不能 kill**（铁律：不在凌瑶空间的进程 AI 不动）
- MED-PPT 改用 19880

### 🟡 问题 5: msl_ma_pwd 弱密码（**未修**，等主人决策）

- 9 位纯小写字母，违反铁律 #14（≥12 位、大小写+数字+特殊字符）
- 影响范围：`alembic.ini` + `config.py` + `.env` + `.env.example` + `docker-compose.yml` 共 4 文件 8 处
- **建议**：独立任务升级为强密码，例如 `Msl_MedPpt_2026_Sec!`

---

## 🔄 启停命令

```bash
# 启动后端
python3 /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/scripts/start_backend_daemon.py

# 启动前端
python3 /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/scripts/start_frontend_daemon.py

# 停止后端（通过 PID 文件）
kill $(cat /tmp/med-ppt-backend.pid)

# 停止前端（通过 PID 文件）
kill $(cat /tmp/med-ppt-frontend.pid)

# 看日志
tail -f /Users/hua/Documents/myself/MED-PPT/logs/backend.out
tail -f /Users/hua/Documents/myself/MED-PPT/logs/frontend.out

# alembic 迁移
cd /Users/hua/Documents/myself/MED-PPT/msl-ma-agent/backend
/Users/hua/.workbuddy/binaries/python/envs/med-ppt/bin/alembic upgrade head
```

---

## 📋 凌瑶空间统一入口

主人重启后：

| 服务 | 端口 | URL | 默认账号 |
|---|---|---|---|
| MED-PPT 前端 | 19881 | http://127.0.0.1:19881/ | （需 seed init） |
| MED-PPT 后端 | 19880 | http://127.0.0.1:19880/health | — |
| PG (lingyao-postgres) | 5432 | `psql -h 127.0.0.1 -U msl_ma -d msl_ma_db` | msl_ma / msl_ma_pwd |

铁律命中: #1 (不主动 push) #2 (重启必发前端地址) #6 (daemon 脚本) #8 (DB 路径单一真理源) #14 (密码强度警告)