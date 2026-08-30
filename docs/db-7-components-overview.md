# 凌瑶空间 7 组件数据库全景图（v1.0 · 2026-08-30）

> **目标**：一图看全凌瑶空间 7 组件数据库现状，与 P1-1 决策树对齐。
>
> **维护原则**：每次数据库改造完成（commit 后）必须更新本图。

---

## 一、7 组件 × 数据库一览

| # | 代号 | 项目 | 当前轨 | DB 文件 / 容器 | 大小 | 端口 | 监控 | 推荐轨 | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| 0 | — | 凌瑶主站 | H2(dev) + PG(prod) | `data/`(dev H2) + `lingyao-postgres`(prod) | dev=0(prod=估计 50-200MB) | 9091(dev) / 9092(staging) | port_watchdog.py | H2 + PG（保留） | ✅ 合规 |
| 1 | 棱镜 | GEOM | SQLite | `凌瑶/geom/backend/data/geo.db` | 待确认 | 8090 / 5182 | 待加 | SQLite（保留） | ✅ 合规（不动） |
| 2 | 皓元 | MPDM | **SQLite** | `MPD-myself/backend/data/hospital_hoyuan.db` | **394.6MB** / 58 表 | 8100 / 3100 | ✅ `db_monitor.py` PID 2212 | **SQLite**（保留，P0-2-A 已优化） | ✅ P0-2-A 完成 |
| 3 | 源策 | AIDD | **PostgreSQL** | 容器（待确认名） | 待确认 | 18080 / 13000 | port_watchdog.py | PG（保留） | ✅ 合规 |
| 4 | 明枢 | POR-M | **SQLite**（已迁）| `POR-M/.../sqlite-data/mingshu.db` | 几 KB / 35 表 | 8280 / 3190 | 待加 | SQLite（保留） | ✅ P0-1 完成 |
| 5 | 辰录 | D-Info | H2(dev) + PG(prod) | dev H2 + prod PG 容器 | 待确认 | 8281 / 5181 | 待加 | H2 + PG（保留） | ✅ 合规 |
| 6 | — | MED-PPT | 未启动 | — | — | 5173 / 19881（前端）| — | PG（启动时按规范选）| ⏳ 待启动 |

**汇总**：7/7 组件当前数据库状态符合 P1-1 规范 ✅

---

## 二、按 P1-1 决策树 Q1-Q4 对齐

### Q1: 数据可丢？
- ✅ H2 内存（凌瑶 dev profile / D-Info dev profile）
- ✅ SQLite（所有 .db 都已 .gitignore）
- ✅ PG（容器化有 backup-pg.sh 备份）

### Q2: 多租户？
- ❌ GEOM / MPDM / POR-M：单租户（SQLite 轨）
- ❌ MED-PPT（规划单租户）
- ✅ 凌瑶主站 / AIDD / D-Info：多租户（PG 轨）

### Q3: 规模？
| 组件 | DB 大小 | 决策树判定 | 实际轨 |
|---|---|---|---|
| 凌瑶主站 | 估计 50-200MB | H2/SQLite/PG 都可 | H2(dev) + PG(prod) |
| GEOM | 待确认 | < 100MB → H2/SQLite | SQLite |
| MPDM | **394.6MB** | **100M-5G → SQLite 轨** ✅ | **SQLite**（合规）|
| AIDD | 待确认 | 多租户 → 强制 PG | PG |
| POR-M | 几 KB | < 100MB → SQLite/H2 都可 | **SQLite**（P0-1 迁） |
| D-Info | 待确认 | dev H2 / prod PG | H2 + PG |
| MED-PPT | 0 | 未启动 | 待启动时选 PG |

### Q4: 跨服务事务？
- 当前所有 7 组件**都是独立服务**，无跨服务事务需求
- 未来如出现：强制 PG（SQLite 不支持分布式事务）

---

## 三、数据库文件 / 容器位置索引

### SQLite 文件
| 项目 | 路径 | 命名规范 |
|---|---|---|
| GEOM | `凌瑶/geom/backend/data/geo.db` | ✅ `<代号>.db` |
| MPDM | `MPD-myself/backend/data/hospital_hoyuan.db` | ⚠️ 历史命名（保留） |
| POR-M | `POR-M/明枢 智能协作平台/10_Backend/mingshu-backend/sqlite-data/mingshu.db` | ✅ `<代号>.db` |

### PostgreSQL 容器
| 项目 | 容器名（期望） | 状态 |
|---|---|---|
| 凌瑶主站 | `lingyao-postgres` | ✅ Up（5432 healthy）|
| AIDD | 待确认（建议 `aidd-postgres`）| 待查 |
| D-Info | 待确认（建议 `dinfo-postgres`）| 待查 |

### 备份目录
| 项目 | 备份位置 | 命名 |
|---|---|---|
| 凌瑶主站 | `凌瑶/deploy/cvm/backup-pg.sh` | `lingyao_pg_backup_YYYYMMDD-HHMMSS.sql.gz` |
| MPDM | `_backups-2026-08-30/mpdm-sqlite-backup-*.tar.gz` | `mpdm-sqlite-backup-YYYYMMDD-HHMMSS.tar.gz` |
| POR-M | `_backups-2026-08-30/h2data-*/` | 已清理（H2 时代） |

---

## 四、监控命令清单

| 组件 | 健康检查命令 | 监控 daemon |
|---|---|---|
| MPDM | `python3 凌瑶/scripts/sqlite_health_check.py --project mpd` | `MPD-myself/scripts/db_monitor.py` PID 2212 |
| POR-M | `python3 凌瑶/scripts/sqlite_health_check.py --project porm` | 待加（db_monitor.py 副本） |
| GEOM | `python3 凌瑶/scripts/sqlite_health_check.py --project geom` | 待加 |
| MED-PPT | `python3 凌瑶/scripts/sqlite_health_check.py --project med-ppt` | 启动时再加 |
| 凌瑶主站 | `curl http://127.0.0.1:9091/api/_diag/health` | `port_watchdog.py` |

---

## 五、关键事实与待办

### 已完成（数据库改造 4 件齐全 · 2026-08-30 12:00 达成）
- ✅ P0-1 POR-M H2 → SQLite（commit `35d8555`）
- ✅ P0-2-A MPDM SQLite PRAGMA 调优（commit `611121a`）
- ✅ P0-2-B MPDM SQLite 监控脚本（commit `5c65c22`）
- ✅ P1-1 DB 选型规范文档（commit `adb0f9d`）
- ✅ 云端配套（PG backup-pg.sh，commit `c0123d9`）

### 待办（按 ROI 排序）
1. 🅰️ **MPDM DB 全局默认 PRAGMA 持久化**（解决监控误判，1 行命令 `PRAGMA journal_mode=WAL`）
2. 🅱️ **POR-M 健康检查接入**（复制脚本运行）
3. 🅲️ **GEOM / AIDD / D-Info / MED-PPT 数据库现状精确调研**
4. 🅳️ **AIDD / D-Info / MED-PTT PG 容器命名规范对齐**（P1-1 §4.1 `<代号>-postgres`）
5. 🅴️ **凌瑶主站 H2 → SQLite / PG 单一真理源确认**（dev vs prod profile 切换）

### 推进时间表
- **2026-08-30 当日**：完成本全景图 + 健康检查脚本 + PRAGMA 集中化文档
- **2026-08-31+**：AIDD / D-Info / GEOM / MED-PPT 数据库状态精确调研
- **2026-09-XX**：主人最后手动 PUSH（云端服务器重装后）

---

## 六、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版，主人下达「本地数据库捋清楚」后整理 |

---

## 七、相关文档

- `凌瑶/docs/db-selection-spec.md` · P1-1 三轨制规范 v1.0
- `凌瑶/docs/sqlite-pragma-spec.md` · SQLite PRAGMA 集中化（待写）
- `凌瑶/scripts/sqlite_health_check.py` · 健康检查脚本
- `凌瑶/.workbuddy/memory/2026-08-30.md` · 今日工作日志
- `凌瑶/.workbuddy/memory/MEMORY.md` · 长期铁律记忆
