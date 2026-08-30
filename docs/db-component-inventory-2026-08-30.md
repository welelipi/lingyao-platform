# 凌瑶空间数据库现状盘点（2026-08-30）

> **目的**：盘点 GEOM / AIDD / Dinfo / MED-PPT 4 个组件的数据库现状，对照 P1-1 决策树识别合规度
>
> **已完成**：POR-M（明枢）/ MPDM（皓元）/ 凌瑶主站（见 db-7-components-overview.md）

---

## 一、GEOM（棱镜）

| 项 | 现状 |
|---|---|
| 仓路径 | `凌瑶/geom/` |
| 数据库类型 | **SQLite 3** |
| DB 文件 | `geo-backend-py/data/geo.db` |
| 文件大小 | **620KB** |
| 表数 | 31 张（ai_platform / brand / channel_account / collect_record / content / report / sys_user / 等）|
| journal_mode | ✅ `wal` |
| busy_timeout | ❌ `0`（默认，有隐患）|
| synchronous | `1` (NORMAL) |
| cache_size | `2000`（默认，约 2MB）|
| 后端栈 | Python 3.13 + FastAPI + SQLAlchemy |
| 状态 | ✅ 符合嵌入式 SQLite 轨（620KB 远低于 5GB 边界）|

### 优化建议（可选）

GEOM 是 Python + FastAPI，参考 MPDM 模式加 SQLAlchemy event listener：

```python
# geo-backend-py/app/database.py 加：
@event.listens_for(engine, "connect")
def set_sqlite_pragmas(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA busy_timeout=5000")  # 0 → 5000
    cursor.execute("PRAGMA cache_size=-64000")  # 2000 → 64MB
    cursor.close()
```

**收益**：消除「偶发锁冲突立即失败」+ 大查询性能提升。

**风险**：🟢 低（GEOM 数据量小，几乎无感）。

---

## 二、AIDD（源策）

| 项 | 现状 |
|---|---|
| 仓路径 | `AIDD/ai-project-copilot/` |
| **多 profile 支持** | ✅ 4 个 profile：private / saas / mysql / test |
| 默认 production | PostgreSQL（PG 主航道轨）|
| test profile | SQLite（WAL + 激进 PRAGMA）|
| 状态 | ✅ **完全合规**，多 profile 设计示范 |

### profile 配置矩阵

| profile | DB | URL | 用途 |
|---|---|---|---|
| `private` | PG | `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:copilot_private}` | 私有化部署 |
| `saas` | PG | `jdbc:postgresql://${DB_HOST:postgres}:${DB_PORT:5432}/${DB_NAME:copilot_saas}` | SaaS 多租户 |
| `mysql` | MySQL | `jdbc:mysql://127.0.0.1:3306/copilot` | 历史兼容（已废弃？） |
| `test` | SQLite | `jdbc:sqlite:.../copilot-dev.db?journal_mode=WAL&synchronous=NORMAL&busy_timeout=60000&cache_size=-8000&foreign_keys=ON&temp_store=MEMORY&mmap_size=268435456` | dev/test |

### 关键学习点

**AIDD 的 SQLite test profile 已经按 P1-1 规范配置**：
- ✅ WAL 模式
- ✅ busy_timeout=60000（比我推荐 5000 还激进 12x）
- ✅ synchronous=NORMAL
- ✅ cache_size=-8000
- ✅ foreign_keys=ON（启用外键约束）
- ✅ temp_store=MEMORY
- ✅ mmap_size=256MB

这是 P1-1 规范的**最佳实践示范**！POR-M / MPDM 后续可参考。

---

## 三、D-Info（辰录）

| 项 | 现状 |
|---|---|
| 仓路径 | `Dinfo/` |
| 数据库类型 | **PostgreSQL + H2**（双 profile）|
| 默认 URL | `jdbc:postgresql://localhost:5432/chenlu_dinfo` |
| profile 切换 | H2（dev）+ PG（prod）|
| 状态 | ✅ **完全合规**，H2 dev + PG prod 标准架构 |

### application.yml 现状

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/chenlu_dinfo}
    driver-class-name: org.postgresql.Driver
```

### 关键学习点

- ✅ 标准 H2（dev）+ PG（prod）架构
- ✅ 通过 `${DB_URL:default}` 支持环境变量覆盖
- ✅ 库名 `chenlu_dinfo`（P1-1 §4.1 命名规范）
- ✅ 默认 PG，dev 用 H2 是凌瑶空间标准做法

---

## 四、MED-PPT

| 项 | 现状 |
|---|---|
| 仓路径 | `MED-PPT/` |
| 数据库类型 | **未启动**（项目尚无后端代码）|
| 状态 | ❌ **未合规**（项目未运行）|
| 推荐 | 启动时按 P1-1 决策树选 PG（主航道轨）|

### 当前项目内容

```
MSL_MA_*.html / .md   ← MSL/MA 系列 PDF 报告 + 原型稿
MSL_MA_PPT生成*.md    ← PPT 生成 MVP 文档
```

**全是文档类资产，没有后端代码，没有数据库。**

### 启动建议

按 P1-1 §3 决策树：
- Q1 数据可丢？→ ❌ 否（生产 PPT 生成数据需保留）
- Q2 多租户？→ ✅ 是（多用户共享 PPT 模板/记录）
- → **直接 PG 轨**

具体配置推荐（启动时套用）：

```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres}:${DB_PORT:5432/${DB_NAME:med_ppt}
    driver-class-name: org.postgresql.Driver
```

容器名：`med-ppt-postgres`

---

## 五、4 组件合规度汇总

| 组件 | 数据库 | 符合 P1-1 | 待优化 |
|---|---|---|---|
| GEOM | SQLite 620KB | ✅ 符合 | 可选 PRAGMA 优化（busy_timeout/cache_size）|
| AIDD | PG/SQLite/MySQL | ✅ **完全合规**（多 profile 示范）| - |
| D-Info | PG/H2 | ✅ 完全合规 | - |
| MED-PPT | 未启动 | ❌ 未合规（项目未运行）| 启动时按 P1-1 选 PG |

---

## 六、关键发现

### 6.1 AIDD 是 P1-1 规范的"最佳实践示范"

`application-test.yml` 的 SQLite 配置：
- `journal_mode=WAL`
- `busy_timeout=60000`（12x 激进）
- `synchronous=NORMAL`
- `cache_size=-8000`
- `foreign_keys=ON`
- `temp_store=MEMORY`
- `mmap_size=268435456`

比 POR-M / MPDM 还激进。POR-M / MPDM 后续可参考 AIDD 配置。

### 6.2 GEOM 缺 busy_timeout 优化

GEOM 是 Python + FastAPI，参考 MPDM 模式加 SQLAlchemy event listener 即可。

**这是 GEOM P0 唯一待办**（低风险低收益，但符合 P1-1 规范）。

### 6.3 MED-PPT 必须用 PG 轨

按 P1-1 §3 Q2 多租户决策，MED-PPT 启动时直接走 PG 主航道轨，不要走 SQLite。

---

## 七、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版 4 组件盘点（GEOM/AIDD/Dinfo/MED-PPT）|

---

## 八、参考文档

- `凌瑶/docs/db-selection-spec.md` · 凌瑶空间 DB 选型规范
- `凌瑶/docs/db-7-components-overview.md` · 7 组件全景图
- `凌瑶/docs/sqlite-pragma-spec.md` · SQLite PRAGMA 集中化规范
- `凌瑶/scripts/db_consistency_audit.py` · 跨组件一致性审计工具
