# SQLite PRAGMA 集中化模板 · AIDD 最佳实践（v1.0 · 2026-08-30）

> **来源**：AIDD `application-test.yml`（实测验证为凌瑶空间 6 SQLite 项目中**唯一全配置 + HikariCP 调优**的样本）
> **目的**：把 AIDD 的 PRAGMA 模式沉淀为跨项目模板，POR-M / MPDM / GEOM 后续 SQLite 项目可直接复用

---

## 一、背景

凌瑶空间 6 个用 SQLite 的子项目，对 PRAGMA 配置参差不齐：

| 项目 | 仓 | PRAGMA 配置方式 | 完整度 |
|---|---|---|---|
| **AIDD**（源策）| `AIDD/ai-project-copilot/backend/` | URL 内嵌 + HikariCP 调优 | **⭐⭐⭐⭐⭐ 完整（6 项 + pool）** |
| MPDM（皓元）| `MPD-myself/backend/` | SQLAlchemy event listener | ⭐⭐⭐⭐ 完整（6 项）但 cache_size 与 AIDD 不一致 |
| POR-M（明枢）| `明枢 智能协作平台/10_Backend/mingshu-backend/` | Java BeanFactoryPostProcessor | ⭐⭐⭐ 部分（待验证）|
| GEOM（棱镜）| `凌瑶/geom/geo-backend-py/` | sqlite3 直连 | ⭐⭐ 仅 3 项（P0-X 补齐中）|

**结论**：**AIDD 是唯一同时满足以下 4 个条件的项目**：
1. 6 项 PRAGMA 全部配置（journal/busy/synchronous/cache/temp_store/mmap）
2. HikariCP 连接池调优（pool=10，最小连接=2）
3. foreign_keys=ON（启用外键约束）
4. connection-test-query 验证（SELECT 1）

---

## 二、AIDD 完整模板（application-test.yml 全文）

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${COPILOT_DB_PATH:/path/to/copilot-dev.db}?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&cache_size=-8000&foreign_keys=ON&temp_store=MEMORY&mmap_size=268435456
    driver-class-name: org.sqlite.JDBC
    hikari:
      # R43-P0 修复：1→10 —— Pipeline 跑批时占满唯一连接，其他 tab 全部 30s 超时 500
      # SQLite WAL 模式支持多读单写，读连接放开到 10 可显著缓解并发饥饿
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      max-lifetime: 600000
      connection-test-query: SELECT 1
  jpa:
    hibernate:
      ddl-auto: update
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    properties:
      hibernate:
        format_sql: false
        jdbc:
          time_zone: GMT+8
  sql:
    init:
      mode: never
```

---

## 三、6 项 PRAGMA 详解

| # | PRAGMA | AIDD 值 | 推荐值 | 作用 |
|---|---|---|---|---|
| 1 | `journal_mode` | WAL | WAL | 日志模式：WAL 支持多读单写，并发提升 10x+ |
| 2 | `busy_timeout` | 5000 | 5000 | 锁等待 ms：默认 0 立即失败，5000 缓解短锁 |
| 3 | `synchronous` | NORMAL | NORMAL | 同步级别：NORMAL 比 FULL 快 2-5x（崩溃风险可接受） |
| 4 | `cache_size` | -8000 | -64000（MPDM）/ -8000（AIDD）| 缓存 KB：负值 = KiB，AIDD -8000 = ~8MB |
| 5 | `temp_store` | MEMORY | MEMORY | 临时表/索引放内存而非磁盘 |
| 6 | `mmap_size` | 268435456 | 268435456 | mmap 读上限字节：256MB，大读性能提升 5-10x |

**额外**：`foreign_keys=ON`（AIDD 独有，外键约束强制）

### 3.1 两种 PRAGMA 设置方式对比

#### 方式 A：URL 内嵌查询参数（AIDD 风格）
```python
url = "jdbc:sqlite:/path/to/db.db?journal_mode=WAL&synchronous=NORMAL&..."
```
- ✅ 优点：单行配置，路径走环境变量即可
- ❌ 缺点：仅对**新建连接**生效，重启前已开连接不应用
- ❌ 缺点：cache_size / temp_store / mmap_size 在某些 JDBC 驱动下被忽略（需测试验证）

#### 方式 B：SQLAlchemy event listener（MPDM 风格）
```python
@event.listens_for(engine, "connect")
def set_sqlite_pragmas(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA busy_timeout=5000")
    # ... 6 项
    cursor.close()
```
- ✅ 优点：每连接强制应用，6 项全支持
- ✅ 优点：可观测（连接日志记录 PRAGMA 应用状态）
- ❌ 缺点：多 30 行代码，需懂 SQLAlchemy event 机制

#### 方式 C：Java BeanFactoryPostProcessor（POR-M 风格）
```java
@Component
public class DataSourceConfig implements BeanFactoryPostProcessor, EnvironmentAware {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 从 Environment 读 spring.datasource.url
        // 用 SQLite JDBC DriverManager.getConnection() 跑 PRAGMA
        // 必须先创建父目录（SQLite JDBC 不会自动建）
    }
}
```
- ✅ 优点：Spring Boot 集成最佳
- ❌ 缺点：必须 @Component + BeanFactoryPostProcessor + EnvironmentAware 三件套，单纯 @Component 不够早
- ❌ 缺点：不能放 spring.factories（被 maven-shade-plugin 错误放置）

**推荐**：新项目用 A 方式（URL 内嵌），改造项目用 B/C 方式（确保全连接应用）

### 3.2 cache_size 跨项目不一致（已发现）

| 项目 | cache_size | 含义 |
|---|---|---|
| AIDD | -8000 | ~8MB |
| MPDM | -64000 | 64MB（更大，推荐）|
| POR-M | ? | 待查 |

**P1-1 决策**：统一为 **-64000（64MB）**（MPDM 值更大更激进，与 256MB mmap_size 匹配）

---

## 四、HikariCP 连接池调优（AIDD 独有）

```yaml
hikari:
  maximum-pool-size: 10        # 默认 1 容易把 Pipeline 跑批占满
  minimum-idle: 2              # 保底连接
  connection-timeout: 30000    # 30s 获取连接超时
  max-lifetime: 600000         # 10 分钟连接生命周期
  connection-test-query: SELECT 1   # 健康检查
```

**为什么 SQLite 需要 pool=10？**
- SQLite WAL 模式支持**多读单写**
- 默认 pool=1 时，Pipeline 跑批占满唯一连接 → 其他 tab 全部 30s 超时 500
- pool=10 释放读并发 → AIDD 实测 Tab 卡顿消失（R43-P0 修复）

**应用建议**：
- Java + HikariCP 项目：必加 pool=10
- Python + SQLAlchemy 项目：调 `pool_size=10` + `pool_pre_ping=True`
- 直接 sqlite3.connect：单连接但 FastAPI 多 worker 自动并发

---

## 五、反模式（不要做）

| 反模式 | 后果 | 正解 |
|---|---|---|
| ❌ 不开 WAL | 读写互斥，并发为 0 | WAL 必开 |
| ❌ busy_timeout=0（默认）| 偶发锁立即失败 | 至少 5000 |
| ❌ synchronous=FULL（默认）| 写慢 2-5x | NORMAL |
| ❌ cache_size=0（默认）| 无缓存 | -8000 ~ -64000 |
| ❌ JDBC 不创建父目录 | 启动失败 | BeanFactoryPostProcessor 早期创建 |
| ❌ PRAGMA 只在直连设（sqlite3 CLI）| 应用层不生效 | 必须在应用连接设 |
| ❌ HikariCP pool=1（默认）| Pipeline 跑批卡死 | pool=10 |
| ❌ connection-test-query 缺失 | 死连接不可检测 | SELECT 1 |

---

## 六、跨项目应用清单

| 项目 | 当前 PRAGMA | 应用本模板的改动 |
|---|---|---|
| AIDD | 6 项 + pool=10 ✅ | **本模板即源**，无需改 |
| MPDM | 6 项 event listener（cache_size=-64000）| 仅需对齐 cache_size=AIDD -8000（不一致点） |
| POR-M | BeanFactoryPostProcessor 3-6 项（待查）| 补齐缺失项 + 加 HikariCP pool=10 |
| GEOM | 3 项 sqlite3 直连 | P0-X 补 4 项（已完成）|
| 未来新项目 | — | **直接套用 AIDD 模板** |

---

## 七、监控与验证

```bash
# 验证当前 PRAGMA（直连 sqlite3 CLI）
sqlite3 /path/to/db.db "PRAGMA journal_mode; PRAGMA busy_timeout; PRAGMA synchronous; PRAGMA cache_size; PRAGMA temp_store; PRAGMA mmap_size;"

# 应用项目:跑 sqlite_health_check.py（凌瑶主仓 scripts/）
python3 /Users/hua/Documents/myself/凌瑶/scripts/sqlite_health_check.py --project mpd
python3 /Users/hua/Documents/myself/凌瑶/scripts/sqlite_health_check.py --project geom
```

---

## 八、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版（从 AIDD application-test.yml 提炼） |

---

## 九、参考

- 凌瑶空间 P1-1 DB 选型规范：`凌瑶/docs/db-selection-spec.md`
- 凌瑶空间 PRAGMA 详细规范：`凌瑶/docs/sqlite-pragma-spec.md`
- AIDD application-test.yml 源文件：`AIDD/ai-project-copilot/backend/src/main/resources/application-test.yml`
- 跨项目审计脚本：`凌瑶/scripts/db_consistency_audit.py`
- 健康检查工具：`凌瑶/scripts/sqlite_health_check.py`