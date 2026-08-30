# 凌瑶空间数据库选型规范（DB Selection Specification）

> **版本**：v1.0 · 2026-08-30
> **维护**：主流程 AI · 对齐凌瑶空间 V3.1 架构
> **归属**：`凌瑶/docs/db-selection-spec.md`
> **状态**：✅ P1-1 完成（与 P0-1 同步落地）

---

## 一、背景与原则

凌瑶空间下 7 组件（主站 + GEOM + MPDM + AIDD + 明枢 + 辰录 + MED-PPT）过去**各项目凭直觉选 DB**：

- 有的用 H2 内存（开发方便但重启丢数据）
- 有的用 H2 文件（10M 没问题，413M 撑爆）
- 有的用 PG（重但稳）
- 有的用 SQLite（轻但父目录不建）

主站 2026-08-30 下达「**数据库 3 轨制架构**」——把选型规则化，避免未来 P0-1（明枢 H2→SQLite 迁移）这类事后回溯。

### 三条铁律

1. **轨定终身**：选定一轨后，业务增长不能轻易换轨（迁移成本高）
2. **dev/prod 不混轨**：dev 内存、prod 文件+容器（不能让生产 H2 内存）
3. **路径/命名规范**：所有 DB 实例遵守统一约定，便于备份/Cron/Migration

---

## 二、三轨制定义

### 轨 1 · 主航道：PostgreSQL 16（容器化）

| 维度 | 规格 |
|---|---|
| **引擎版本** | `postgres:16-alpine`（Docker 镜像） |
| **用途** | 生产级多租户 + 高并发 + 大数据量 |
| **特点** | 事务完整、JSON 支持、行级锁、逻辑复制 |
| **部署** | Docker 容器，命名 `<项目代号>-postgres` |
| **连接池** | HikariCP（Spring Boot 默认） |
| **关键 ENV** | `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `TZ=Asia/Shanghai` |
| **Schema 策略** | dev: `create-drop` · private: `update` |

**适用项目**：

| 项目 | 库名 | 容器名 | 当前状态 |
|---|---|---|---|
| 凌瑶主站 | `lingyao` | `lingyao-postgres` | ✅ Up（本地 Docker）|
| AIDD（源策） | `aidd` | `aidd-postgres` | ✅ 生产可用 |
| D-Info（辰录） | `dinfo` | `dinfo-postgres` | ✅ 生产可用 |
| MED-PPT | `med_ppt` | `med-ppt-postgres` | ⏳ 待启动 |
| MPDM（皓元）| `mpdm` | `mpdm-postgres` | ⏳ P0-2 落地后 |

### 轨 2 · 嵌入式：SQLite 3（本地文件）

| 维度 | 规格 |
|---|---|
| **引擎版本** | SQLite ≥ 3.39（已测 3.45.3.0） |
| **JDBC** | `org.xerial:sqlite-jdbc:3.45.3.0` |
| **Hibernate Dialect** | `org.hibernate.community.dialect.SQLiteDialect`（6.x 起拆出 community） |
| **用途** | 单租户 + 本地数据 + 轻量部署 |
| **特点** | 零运维、单文件、WAL 高并发读、跨平台 |
| **部署** | 项目根目录 `sqlite-data/<代号>.db` |
| **文件结构** | 主库 + `*.db-shm` + `*.db-wal`（WAL 模式自动产生）|

**URL 标准格式**：
```
jdbc:sqlite:${user.dir}/sqlite-data/<代号>.db?journal_mode=WAL&busy_timeout=5000
```

**适用项目**：

| 项目 | DB 文件 | 容器 | 当前状态 |
|---|---|---|---|
| GEOM（棱镜） | `geom-data/geom.db` | 无 | ✅ 生产可用 |
| 明枢（POR-M） | `sqlite-data/mingshu.db` | 无 | ✅ P0-1 已迁 |
| MPDM（皓元） | 暂未启用 | 无 | ⏳ P0-2 后改 PG |

### 轨 3 · 开发轨：H2 内存库（仅 dev profile）

| 维度 | 规格 |
|---|---|
| **引擎版本** | H2 2.x（Spring Boot 默认带） |
| **模式** | `mem` + `DB_CLOSE_DELAY=-1` 保持连接 |
| **Schema 策略** | `create-drop` |
| **用途** | 单测 + 重启可丢数据 + 极快启动 |
| **特点** | 启动 3 秒、无外部依赖、不污染磁盘 |
| **禁用** | ❌ **生产环境禁止使用**（重启全丢） |

**适用项目**：

| 项目 | 用法 | 说明 |
|---|---|---|
| 凌瑶主站 dev | `dev,h2` profile | 重启数据清零，专为开发体验 |
| D-Info dev | `dev` profile | 同上 |
| 任意子模块单测 | `@DataJpaTest` 默认 | Spring Boot Test 默认 H2 |

---

## 三、选型决策树

```
新模块 / 新功能 / 新数据库 → 问 4 个问题
```

### Q1：生产环境数据能否丢失？

- **能丢** → 🚂 轨 3 H2（仅 dev/单测）
- **不能丢** → 进入 Q2

### Q2：是否多租户？

- **多租户**（按机构/学校/地区隔离）→ 🚢 轨 1 PostgreSQL
- **单租户** → 进入 Q3

### Q3：预计数据规模？

| 数据规模 | 推荐轨 |
|---|---|
| < 100MB | 🚂 轨 3 H2（如单测）|
| 100MB - 5GB | 📦 轨 2 SQLite |
| > 5GB | 🚢 轨 1 PG |
| 增长不确定 | 🚢 轨 1 PG（一次到位避免迁移）|

### Q4：是否需要跨服务共享数据？

- **是**（多服务读写同一份数据）→ 🚢 轨 1 PG（容器）
- **否**（单服务独享）→ 🚂 轨 3 / 📦 轨 2 / 🚢 轨 1 都可，按 Q3 决定

### 决策流程图

```
              ┌─ 是 ─→ H2（仅 dev）
              │
   数据可丢？─┤
              │                                              ┌─ 是 ─→ PG
              │                              ┌─ 多租户？──┤
              │                              │            └─ 否
              │                              │             │
   数据不可丢─┤                               │             │
              │                              │             ▼
              │                              │   ┌─ > 5GB ─→ PG
              │                              │   │
              │                              │   ├─ 100M-5G ─→ SQLite
              │                              │   │
              │                              │   └─ < 100M ─→ SQLite
              │                              │
              │                              └─ 跨服务共享？
              │                                  │
              │                                  └─ 是 ────────→ PG
              │
              └─ 其他
```

---

## 四、命名与路径规范

### 4.1 PG 容器 / 库命名

```
容器名: <项目代号>-postgres
库名:   <项目代号>           （dev）
       <项目代号>_staging   （staging）
       <项目代号>           （prod，不加后缀）
用户名: <项目代号>_app      （不用 postgres）
```

示例：
- 凌瑶主站 dev：`lingyao-postgres` / `lingyao` / `lingyao_app`
- GEOM prod：`geom-postgres` / `geom` / `geom_app`
- MPDM staging：`mpdm-postgres` / `mpdm_staging` / `mpdm_app`

### 4.2 SQLite 文件 / 目录

```
项目根目录/
└── sqlite-data/           ← 父目录
    ├── mingshu.db         ← 主库文件
    ├── mingshu.db-shm     ← WAL 共享内存
    └── mingshu.db-wal     ← WAL 预写日志
```

**`.gitignore` 必须包含**：
```gitignore
# SQLite 运行时数据（v3.1 数据库 3 轨制 · 嵌入式轨）
*.db
*.db-shm
*.db-wal
sqlite-data/
```

### 4.3 备份命名

```
PG:    <代号>_pg_backup_<YYYYMMDD>.dump
SQLite: <代号>_sqlite_backup_<YYYYMMDD>.db
目录:   _backups-<YYYY-MM-DD>/
```

---

## 五、迁移 SOP（参考 P0-1 实战）

### H2 → SQLite 迁移（适用于明枢/GEOM/MPDM 单租户模块）

| 阶段 | AI | 主人 |
|---|---|---|
| 1. 备份 `h2data/` 到 `_backups-<日期>/` | ✅ 自动 | check |
| 2. `pom.xml` 加 SQLite JDBC 依赖 | ✅ 自动 | - |
| 3. 改 `application-<profile>.yml` URL/driver/dialect | ✅ 自动 | - |
| 4. 写 `DataSourceConfig`（BeanFactoryPostProcessor） | ✅ 自动 | - |
| 5. `.gitignore` 加 SQLite 行 | ✅ 自动 | - |
| 6. 编译 + 启动 + 验证表数 + WAL | ✅ 自动 | check |
| 7. commit 本地 | ✅ 自动 | - |
| 8. push | ❌ **主人说才推** | 推 |

### H2 → PG 迁移（适用于多租户/大数据量模块，如 MPDM）

| 阶段 | AI | 主人 |
|---|---|---|
| 1. 备份 H2 文件 | ✅ 自动 | check |
| 2. Docker 起 `<代号>-postgres` 容器 | ✅ 自动 | - |
| 3. `pom.xml` 换 PG 驱动 + Hibernate dialect | ✅ 自动 | - |
| 4. `application-<profile>.yml` 切 PG | ✅ 自动 | - |
| 5. 写迁移脚本（H2 → PG schema 转换）| ✅ 自动 | check |
| 6. 启动验证表数 + 数据导入 | ✅ 自动 | check |
| 7. commit 本地 | ✅ 自动 | - |
| 8. push | ❌ 主人说才推 | 推 |

### SQLite → PG 迁移（嵌入式轨 → 主航道轨）

| 阶段 | AI | 主人 |
|---|---|---|
| 1. 备份 `.db` + `.db-wal` | ✅ 自动 | check |
| 2. Docker 起 `<代号>-postgres` | ✅ 自动 | - |
| 3. 用 `sqlite3 .dump` 转 SQL，再适配 PG 方言 | ✅ 自动 | check |
| 4. `pom.xml` 换 PG + Dialect | ✅ 自动 | - |
| 5. 启动验证 + 数据对账 | ✅ 自动 | check |
| 6. commit 本地 | ✅ 自动 | - |
| 7. push | ❌ 主人说才推 | 推 |

---

## 六、反模式（避坑指南）

| # | 反模式 | 后果 | 替代方案 |
|---|---|---|---|
| 1 | ❌ 生产环境用 H2 | 重启全丢 + 无备份 | 🚢 PG（即使是单租户）|
| 2 | ❌ SQLite 父目录不预创建 | 启动报 `path to '...': ... ` | BeanFactoryPostProcessor + `Files.createDirectories()` |
| 3 | ❌ SQLite 路径硬编码 `~/xxx.db` | 跨机器失效 | `${user.dir}/sqlite-data/<代号>.db` |
| 4 | ❌ PG 用 root 用户连接 | 安全风险 + 触发权限冲突 | 业务专用 `<代号>_app` 用户 |
| 5 | ❌ PG 容器卷直接删 | 数据不可逆丢失 | 备份后再删（`pg_dump`）|
| 6 | ❌ SQLite 不开 WAL | 写并发冲突 | URL 加 `journal_mode=WAL&busy_timeout=5000` |
| 7 | ❌ 选型凭感觉 | 事后回溯成本高 | 按 Q1-Q4 决策树走 |
| 8 | ❌ dev/prod 不同步 | dev 通 prod 挂 | dev=H2 + prod=PG，profile 切 |

---

## 七、当前项目 DB 选型状态

| # | 项目 | 代号 | 当前轨 | 推荐轨 | 状态 | 优先级 |
|---|---|---|---|---|---|---|
| 0 | 凌瑶主站 | - | H2(dev) + PG(prod) | 保留 | ✅ 合规 | - |
| 1 | GEOM | 棱镜 | SQLite | 保留 | ✅ 合规 | - |
| 2 | MPDM | 皓元 | **H2 文件 413MB** | **PG** | 🔴 待迁 | **P0-2** |
| 3 | AIDD | 源策 | PG | 保留 | ✅ 合规 | - |
| 4 | POR-M | 明枢 | H2 → **SQLite** | 保留 | ✅ 已迁 | **P0-1 ✅** |
| 5 | D-Info | 辰录 | H2(dev) + PG(prod) | 保留 | ✅ 合规 | - |
| 6 | MED-PPT | - | 未启动 | PG | ⏳ 启动时按规范选 | P1-2 |

---

## 八、关键事实与铁律（贴 CODE 友好）

### 事实 1：SQLite JDBC 不自动创建父目录
- **现象**：启动报 `path to '...sqlite-data/x.db': '...': No such file or directory`
- **方案**：`BeanFactoryPostProcessor` + `EnvironmentAware` 中 `Files.createDirectories(parent)`（P0-1 已落地 `DataSourceConfig.java`）

### 事实 2：Spring Boot 构造器/Listener 太晚
- **现象**：`@Configuration` 构造器 / `@Component implements ApplicationListener` 都不触发
- **原因**：DataSource Bean 是 lazy 的，触发晚于所有常规生命周期
- **方案**：唯一可靠是 `BeanFactoryPostProcessor` + `EnvironmentAware`（P0-1 唯一可行方案）

### 事实 3：H2 PID 占 jar
- **现象**：`kill <PID>` 后才能替换 jar
- **方案**：启动前 `ps -ef | grep java | grep <module>` 找 PID

### 事实 4：PG Docker 卷旧数据陷阱
- **现象**：compose up 后报 `password authentication failed` 但 `psql` 说 `role "postgres" does not exist`
- **原因**：数据卷是上次容器留下的，**首次 init 跳过**，env 不生效
- **方案**：dev + 无业务数据 → `docker volume rm <vol>` 后重启；prod → `pg_hba.conf` 临时改 trust + `ALTER USER`

### 事实 5：WAL 模式并发安全
- **现象**：多进程读写 SQLite 报 `database is locked`
- **方案**：URL 加 `journal_mode=WAL&busy_timeout=5000`（5 秒等待）

---

## 九、变更记录

| 版本 | 日期 | 变更 | 触发 | 落地 |
|---|---|---|---|---|
| v1.0 | 2026-08-30 | 初版：三轨制定义 + 决策树 + 反模式 + 项目状态 | 主站下达「数据库 3 轨制架构」 | P1-1 commit |

---

## 十、待主人确认 / 待办

- [ ] **待主人 check** 文档内容是否完整
- [ ] **待主流程 push**（数据库改造完成 4 件齐全后）
- [ ] **P0-2**：MPDM H2 → PG（按本文档 Q3 决策，>5GB 必须 PG）
- [ ] **P1-2**：MED-PPT 启动时按本文档选轨（推荐 PG）
- [ ] **P1-3**：所有未来新模块按 Q1-Q4 走决策树

---

## 附录 A：参考实战

- **P0-1 POR-M H2 → SQLite 迁移**：见 `凌瑶/.workbuddy/memory/2026-08-30.md` P0-1 章节
- **8 阶段完整 SOP**：见本文档第五章「迁移 SOP」
- **V3.1 七组件架构**：见 `凌瑶/.workbuddy/memory/MEMORY.md` § 凌瑶空间 7 组件架构 V3.1

---

> **下次更新触发**：轨 2/轨 1 任一项目状态变更、新增组件、或选型争议时。
