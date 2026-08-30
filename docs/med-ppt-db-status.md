# MED-PPT 数据库现状盘点（v1.0 · 2026-08-30）

> **范围**：msl-ma-agent 后端数据库选型 + 配置 + 当前状态
> **目的**：按 P1-1 决策树合规审查；识别问题清单；产出启动 SOP
> **状态**：MED-PPT 当前**未启动**（无服务进程，无 PG 容器 `msl_ma_db`）

---

## 一、路径与现状

| 项 | 值 |
|---|---|
| 路径 | `MED-PPT/msl-ma-agent/backend/` |
| 栈 | FastAPI + SQLAlchemy + Alembic + Pydantic Settings |
| 数据库 | PostgreSQL（asyncpg 异步 + psycopg2 同步）|
| 当前状态 | ❌ **未启动**（无 `msl_ma_db` 容器、无 FastAPI 进程）|
| 启动方式 | 待主人决策（云端部署 / 本地 daemon）|

---

## 二、P1-1 决策树审查

### Q1 数据可丢？
- ❌ 否（PPT 生成记录 / 业务对话 / 技能包配置需要保留）

### Q2 多租户？
- ✅ 是（凌瑶空间统一 SSO）
- **强制走 PG 轨**

### Q3 规模？
- 未知（未启动，无数据量评估）
- 假设 < 5GB → 可继续 PG（凌瑶主航道）

### Q4 跨服务？
- ✅ 是（与凌瑶主站 SSO 关联 / D-Info 飞书 API 关联）

### 决策结果
✅ **主航道 PostgreSQL**（P1-1 决策合规）

---

## 三、当前 DB 配置

### 3.1 config.py（pydantic-settings）
```python
database_url: str = "postgresql+asyncpg://msl_ma:msl_ma_pwd@localhost:5432/msl_ma_db"
database_url_sync: str = "postgresql+psycopg2://msl_ma:msl_ma_pwd@localhost:5432/msl_ma_db"
database_pool_size: int = 10
database_max_overflow: int = 20
```

### 3.2 alembic.ini（迁移）
```ini
sqlalchemy.url = postgresql+psycopg2://pmp:pmp_dev_pwd_2026@127.0.0.1:5432/msl_ma_db
```

### 3.3 跨项目 DB URL 一致性
- Dinfo: `jdbc:postgresql://localhost:5432/chenlu_dinfo` (user=`pmp`)
- 凌瑶主仓: `jdbc:postgresql://localhost:5432/lingyao` (user=`lingyao_app`)
- MED-PPT: `postgresql+asyncpg://msl_ma@localhost:5432/msl_ma_db` (user=`msl_ma`)

✅ 命名规范（`msl_ma_db` 对齐 `msl_ma` 用户）

---

## 四、问题清单

### 🔴 P0 · alembic.ini 命名不一致

**症状**：alembic.ini 用 `pmp:pmp_dev_pwd_2026`，但 config.py 用 `msl_ma:msl_ma_pwd`

**风险**：
- alembic upgrade head 时用 pmp 认证 → PG 容器里可能不存在 pmp 角色 → 迁移失败
- 与 config.py 运行时认证不一致 → 双重身份管理混乱

**修复**：
```ini
# alembic.ini line ~9
# 旧：sqlalchemy.url = postgresql+psycopg2://pmp:pmp_dev_pwd_2026@127.0.0.1:5432/msl_ma_db
# 新：
sqlalchemy.url = postgresql+psycopg2://msl_ma:msl_ma_pwd@127.0.0.1:5432/msl_ma_db
```

**所属仓**：MED-PPT 仓（`MED-PPT/msl-ma-agent/backend/`）
**修复成本**：1 行改动 + 重启 alembic

### 🟡 P1 · MED-PPT 缺 PG 容器

**症状**：MED-PPT 当前没有对应的 PG 容器（`msl_ma_db` 库不存在）

**修复**：
```bash
# 仿照 lingyao-postgres 容器
docker run -d --name msl-med-ppt-postgres --restart unless-stopped \
  -p 5432:5432 \
  -e POSTGRES_DB=msl_ma_db \
  -e POSTGRES_USER=msl_ma \
  -e POSTGRES_PASSWORD='<from .env>' \
  -e LANG=en_US.utf8 \
  -v msl-med-ppt-pg-data:/var/lib/postgresql/data \
  m.daocloud.io/docker.io/library/postgres:16-alpine
```

### 🟡 P1 · MED-PPT 启动 SOP 缺失

**症状**：当前 MED-PPT 后端进程未运行，前端也未启

**修复**：见第五节"启动 SOP"

### 🟢 P2 · backup-pg.sh 缺失（云端）

MED-PPT 当前无 PG 备份脚本（与凌瑶主仓 backup-pg.sh 同款）

### 🟢 P2 · HikariCP 等价配置未确认

SQLAlchemy 当前 `pool_size=10 + max_overflow=20`（OK），但 `pool_pre_ping` 等健康检查参数是否设置？

---

## 五、启动 SOP（待主人决策）

### Step 1 · 创建 PG 容器

```bash
# 主人 SSH 到 staging 或本地 daemon
docker run -d --name msl-med-ppt-postgres \
  --restart unless-stopped \
  -p 5432:5432 \
  -e POSTGRES_DB=msl_ma_db \
  -e POSTGRES_USER=msl_ma \
  -e POSTGRES_PASSWORD='<.env 里的密码>' \
  -e LANG=en_US.utf8 \
  -v msl-med-ppt-pg-data:/var/lib/postgresql/data \
  postgres:16-alpine
```

### Step 2 · 修 alembic.ini 命名

```bash
cd MED-PPT/msl-ma-agent/backend
# 编辑 alembic.ini line 9
sed -i.bak 's|pmp:pmp_dev_pwd_2026|msl_ma:msl_ma_pwd|g' alembic.ini
```

### Step 3 · 运行 alembic 迁移

```bash
# 在 msl-ma-agent/backend/ 目录下
alembic upgrade head
```

### Step 4 · 启动 FastAPI

```bash
# daemon 化
python3 scripts/start_backend_daemon.py  # 或类似脚本（待 MED-PPT 仓补充）
```

### Step 5 · 启动前端 Vite

```bash
cd ../frontend
python3 scripts/start_frontend_daemon.py  # 端口 19881（msl-ma-agent-frontend-local）
```

### Step 6 · 验证

```bash
curl http://127.0.0.1:19880/api/v1/version
curl http://127.0.0.1:19881/
```

---

## 六、与凌瑶空间 P1-1 对齐状态

| P1-1 规范 | MED-PPT 当前 | 状态 |
|---|---|---|
| 选型决策树 | ✅ PG（多租户 + 跨服务）| 合规 |
| 命名规范 | ⚠️ alembic 用 pmp/config 用 msl_ma | 待修 |
| 容器命名 | ❌ 无容器（msl-med-ppt-postgres）| 待建 |
| 备份脚本 | ❌ 无 backup-pg-msl.sh | 待写 |
| 监控 | ❌ 无 db-monitor | 待写 |
| HikariCP 等价 | ✅ pool_size=10 + max_overflow=20 | 合规 |
| JPA / SQLAlchemy dialect | ✅ PostgreSQLDialect | 合规 |
| Profile 多轨 | ⚠️ dev=PG（无 dev H2 fallback）| 主人决策 |

---

## 七、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版现状盘点 |

---

## 八、参考

- P1-1 DB 选型规范：`凌瑶/docs/db-selection-spec.md`
- 7 组件数据库全景：`凌瑶/docs/db-7-components-overview.md`
- 跨项目审计：`凌瑶/scripts/db_consistency_audit.py`
- 凌瑶主仓 backup-pg.sh：`凌瑶/deploy/cvm/backup-pg.sh`（参考模板）
- MED-PPT config.py：`MED-PPT/msl-ma-agent/backend/app/core/config.py`