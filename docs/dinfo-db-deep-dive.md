# Dinfo 辰录 · 数据库深化指南（v1.0 · 2026-08-30）

> **范围**：Dinfo 后端 4 个 profile + PostgreSQL + H2 配置细节审查
> **目的**：按 P1-1 + AIDD 模板对照审查；产出改进建议清单
> **所属仓**：Dinfo 仓（`Dinfo/backend/`）—— 本指南**不直接改 Dinfo 仓代码**，等 Dinfo 窗口主人确认

---

## 一、4 个 profile 全景

| profile | 端口 | DB 类型 | DB URL | username | 状态 |
|---|---|---|---|---|---|
| **default** | 8281 | PostgreSQL | `jdbc:postgresql://localhost:5432/chenlu_dinfo` | `pmp` | ✅ 主航道 |
| **dev** | 8281 | H2 内存 | `jdbc:h2:mem:dinfo;MODE=MySQL;DB_CLOSE_DELAY=-1` | `sa` | ✅ 开发轨 |
| **fat** | 8281 | PostgreSQL | `jdbc:postgresql://localhost:5432/chenlu_dinfo` | `pmp` | ✅ 主航道（与 default 一致）|
| **prod** | 8281 | PostgreSQL | `jdbc:postgresql://localhost:5432/chenlu_dinfo` | `pmp` | ✅ 主航道 |

**P1-1 决策树审查**：
- Q1 数据可丢？dev 是（重启用），其他否 → 决策正确
- Q2 多租户？否（辰录是单租户）+ dev H2 是开发轨 → ✅
- Q3 规模？< 5GB → ✅
- Q4 跨服务？是（与凌瑶主站 SSO 共享 JWT）→ ✅ 主航道 PG
- **结论**：✅ P1-1 合规（4 profile 设计清晰）

---

## 二、与 AIDD 模板对比

### 2.1 AIDD 模板（HikariCP 调优）
```yaml
hikari:
  maximum-pool-size: 10
  minimum-idle: 2
  connection-timeout: 30000
  max-lifetime: 600000
  connection-test-query: SELECT 1
```

### 2.2 Dinfo 现状（4 个 profile 全部未配）
```yaml
# Dinfo application.yml + dev.yaml + fat.yaml + prod.yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chenlu_dinfo
    driver-class-name: org.postgresql.Driver
    username: pmp
    password: pmp_dev_pwd_2026
  # ❌ 缺 hikari 配置（用默认 maximum-pool-size=10 实测可行，但 minimum-idle/timeout 未显式声明）
```

**Dinfo 实际行为**：
- HikariCP 默认 `maximum-pool-size=10`（巧合与 AIDD 一致）
- `minimum-idle` 默认 = `maximum-pool-size`（即 10）—— 启动时开 10 连接，对 PG 容器压力较大
- 无 `connection-test-query` → 死连接检测依赖驱动心跳

**改进建议**（不在本窗口做）：
- 4 个 profile yml 全部加 `hikari:` 配置块（minimum-idle=2 节省 PG 连接）
- dev profile（H2 内存库）`maximum-pool-size` 调低到 2（H2 in-memory 单连接即可）

---

## 三、PostgreSQL 容器使用现状

### 3.1 容器共享（与凌瑶主仓冲突）

Dinfo 用 `pmp-postgres` 容器（与 PMP 旧项目共享）：
```
pmp-postgres
├── pmp (库)
├── chenlu_dinfo (库)  ← Dinfo 用
└── （其他 PMP 业务库）
```

**与凌瑶主仓对比**：
- 凌瑶主仓 = `lingyao-postgres`（独立容器）
- Dinfo = `pmp-postgres`（共享容器）

**风险**：
- 共享 PG 容器 → DB 备份/恢复粒度差（pg_dump 必须指定库名）
- `pmp` 角色 vs `lingyao_app` 角色权限边界 → 当前安全
- pmp-postgres 容器 Exited (128) 在 2026-08-29 18:27 → 历史不稳定

**建议**：
- 长期看，Dinfo 应迁独立容器 `dinfo-postgres`
- 短期：保持共享，但加 backup-pg-dinfo.sh 独立脚本

---

## 四、缺项清单（建议改进）

### 🟡 P1 · HikariCP 连接池参数显式配置

**改动**：4 个 profile yml 加 `hikari:` 配置块

**工作量**：8 行改动（4 profile × 2 行）

**所属仓**：Dinfo 仓

### 🟡 P1 · backup-pg-dinfo.sh 备份脚本

**缺失**：当前无 Dinfo 独立 PG 备份脚本（与凌瑶主仓 backup-pg.sh 同款）

**模板**：参考 `凌瑶/deploy/cvm/backup-pg.sh`，改为：
```bash
PG_DB=chenlu_dinfo
BACKUP_DIR=/data/db-backup/dinfo
KEEP_DAYS=7
```

**工作量**：~120 行（与 backup-pg.sh 类似）

**所属仓**：凌瑶主仓 `deploy/cvm/backup-pg-dinfo.sh`（共用资产）

### 🟢 P2 · Flyway/Liquibase 迁移目录

Dinfo 当前用 `ddl-auto: update`（JPA 自动建表/迁移），风险：
- 删除字段不会自动 DROP COLUMN
- 字段类型变更不处理
- 生产环境 update 不安全（应 validate）

**建议**：未来引入 Flyway，写 `db/migration/V1__init.sql` 等

### 🟢 P2 · PG pg_isready 健康检查

Dinfo 后端启动时未先检查 PG 可达性 → PG 未起时 java 启动失败但 fail-fast 不优雅

**建议**：在 DataSourceConfig 加 `@PostConstruct` 健康检查

---

## 五、与 AIDD 模板同步建议清单（按 P1-1 优先级）

| # | 改动 | 优先级 | 所属仓 | 工作量 |
|---|---|---|---|---|
| 1 | 4 profile yml 加 `hikari:` 配置块 | P1 | Dinfo | 8 行 |
| 2 | backup-pg-dinfo.sh 备份脚本 | P1 | 凌瑶主仓 | ~120 行 |
| 3 | dev profile H2 池调小（max=2）| P1 | Dinfo | 2 行 |
| 4 | DataSourceConfig 加 PG 健康检查 | P2 | Dinfo | 20 行 |
| 5 | 引入 Flyway 迁移目录 | P2 | Dinfo | 半天 |
| 6 | Dinfo 迁独立 PG 容器 | P3 | 凌瑶主仓 + Dinfo | 半天 |

**P1 全做完 → Dinfo 也对齐 AIDD P1-1 最佳实践**

---

## 六、当前生产稳定性

| 项 | 状态 |
|---|---|
| 后端 8281 | 🟢 PID 56534，D1.1.3，dev/H2 in-memory，HTTP 200 |
| 前端 5181 | 🟢 PID 56557，Vite 5.4.21，HTTP 200 |
| reloadAll schedule | 🟢 8 个 scheduleReload markReady，30 分钟一次 |
| 503 拦截 | 🟢 D1.1.3 的 C 方案生效（数据准备中拦截）|
| H2 in-memory | 🟢 稳态跑 67-103s |
| PG 容器（pmp-postgres）| 🟡 历史 Exited (128) 2026-08-29 18:27，需关注 |

---

## 七、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版深化指南（AIDD 模板对照 + P1 改进清单）|

---

## 八、参考

- AIDD 模板：`凌瑶/docs/sqlite-pragma-template-aidd.md`
- P1-1 规范：`凌瑶/docs/db-selection-spec.md`
- 7 组件全景：`凌瑶/docs/db-7-components-overview.md`
- 凌瑶主仓 backup-pg.sh：`凌瑶/deploy/cvm/backup-pg.sh`
- Dinfo application.yml：`Dinfo/backend/src/main/resources/application.yml`
- Dinfo 4 profile：`Dinfo/backend/src/main/resources/application-{dev,fat,prod}.yaml`