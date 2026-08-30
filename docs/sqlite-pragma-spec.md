# SQLite PRAGMA 集中化规范（v1.0 · 2026-08-30）

> **目标**：把凌瑶空间所有 SQLite 模块的 PRAGMA 调优经验沉淀，给 POR-M / MPDM / GEOM / 未来 SQLite 模块直接 copy。
>
> **对齐**：`db-selection-spec.md` §2.2 嵌入式轨 + §6.3 工具复用

---

## 一、6 项核心 PRAGMA（生产环境推荐值）

| # | PRAGMA | 推荐值 | 含义 | 默认值 | 影响 |
|---|---|---|---|---|---|
| 1 | `journal_mode` | `wal` | WAL 模式（Write-Ahead Logging）| `delete` | 🔥 并发读写提升 10x+ |
| 2 | `busy_timeout` | `5000` | 锁等待超时（毫秒）| `0`（立即失败）| 🔥 消除「偶发锁冲突立即失败」|
| 3 | `synchronous` | `1` (NORMAL) | 同步级别（WAL 下 NORMAL 安全）| `2` (FULL) | 🚀 WAL 下写快 2-3 倍 |
| 4 | `cache_size` | `-64000` | 负数 = KB，正数 = 页数（默认页 4KB）| `2000`（约 2MB）| 🚀 64MB page cache 减少磁盘 IO |
| 5 | `temp_store` | `2` (MEMORY) | 临时表存储位置 | `0` (DEFAULT) | 🚀 临时表放内存 |
| 6 | `mmap_size` | `268435456` (256MB) | mmap 大小 | `0`（禁用）| 🚀 大查询顺序读直接走内存 |

**通用规律**：
- `journal_mode=wal` 是必须项，其他 5 项是性能优化项
- WAL 模式下，`synchronous=NORMAL` 比 `FULL` 安全（已落盘）
- `busy_timeout=5000` 是「不立即失败」的最小值，业务高峰期可调到 10000

---

## 二、Spring Boot 模式（Java / POR-M）

### 2.1 配置文件 `application-h2.yml`（或 application-sqlite.yml）

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${user.dir}/sqlite-data/mingshu.db?journal_mode=WAL&busy_timeout=5000
    driver-class-name: org.sqlite.JDBC
    hibernate:
      dialect: org.hibernate.community.dialect.SQLiteDialect
```

### 2.2 父目录自动创建（**踩坑 4 次总结**）

**问题**：SQLite JDBC **不会**自动创建父目录（H2 会）。

**解决方案**：`BeanFactoryPostProcessor + EnvironmentAware`（最早阶段触发）

```java
package com.lingyao.mingshu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class DataSourceConfig implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:sqlite:")) {
            log.info("Skip SQLite parent dir setup, not a SQLite datasource");
            return;
        }

        // 解析 jdbc:sqlite: 后面的路径（含 ${} 占位符）
        String resolvedUrl = environment.resolvePlaceholders(url);
        String dbPath = resolvedUrl
            .substring("jdbc:sqlite:".length())
            .split("\\?")[0];  // 去掉 ?journal_mode=WAL 参数

        Path parentDir = Paths.get(dbPath).getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
                log.info("✅ Created SQLite parent dir: {}", parentDir.toAbsolutePath());
            } catch (Exception e) {
                log.error("❌ Failed to create SQLite parent dir: {}", parentDir, e);
                throw new RuntimeException("Cannot create SQLite parent dir", e);
            }
        }
    }
}
```

**为什么用 `BeanFactoryPostProcessor + EnvironmentAware` 而不是其他？**

| 方案 | 触发时机 | 可行性 |
|---|---|---|
| `@Configuration` 构造器 | Bean 创建时 | ❌ **不触发**（lazy bean）|
| `@Component` + `ApplicationListener` | 上下文启动后 | ❌ **不触发**（lazy listener）|
| `BeanFactoryPostProcessor` + `EnvironmentAware` | **最早阶段** + 自动注入 Environment | ✅ **唯一可行** |
| `spring.factories` 自动注册 | 太晚 | ❌ Boot 3 不支持 |

### 2.3 添加 SQLite JDBC 依赖（`pom.xml`）

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.3.0</version>
</dependency>
```

### 2.4 Hibernate 方言（Spring Boot 3.x）

```yaml
spring:
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
```

⚠️ 注意：Hibernate 6+ 把 SQLite 方言移到 `org.hibernate.community.dialect`（不是 `org.hibernate.dialect`）。

---

## 三、FastAPI 模式（Python / MPDM / GEOM）

### 3.1 SQLAlchemy event listener（`app/database.py`）

```python
import os
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker, DeclarativeBase

# 配置
DB_PATH = os.environ.get("MPD_DB_PATH", "data/hospital_hoyuan.db")
DB_DIR = os.path.dirname(DB_PATH)
os.makedirs(DB_DIR, exist_ok=True)  # Python os.makedirs 会创建父目录（不像 JDBC 坑）

DB_URL = f"sqlite:///{DB_PATH}"
engine = create_engine(DB_URL, connect_args={"check_same_thread": False})


# ⭐ 关键：连接级 PRAGMA event listener
@event.listens_for(engine, "connect")
def set_sqlite_pragmas(dbapi_connection, connection_record):
    """MPDM SQLite 性能调优 (P0-2-A)"""
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA busy_timeout=5000")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=-64000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA mmap_size=268435456")
    cursor.close()


SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
```

### 3.2 `os.makedirs` 优势

Python 的 `os.makedirs(path, exist_ok=True)` **会自动创建父目录**，不像 SQLite JDBC 那么坑。这是 FastAPI 比 Spring Boot 在 SQLite 上更友好的关键点。

### 3.3 添加 SQLAlchemy 依赖（`requirements.txt`）

```
sqlalchemy>=2.0.0
sqlite3  # Python 内置
```

---

## 四、POR-M / MPDM 实战经验

### 4.1 POR-M（明枢）P0-1 实战

**8 阶段迁移**（H2 → SQLite）：

1. 备份 `h2data/` → `_backups-2026-08-30/`
2. 改 `pom.xml`：加 `sqlite-jdbc` 3.45.3.0
3. 加 `DataSourceConfig.java`（父目录自动创建）
4. 改 `application-h2.yml`：url 改成 `jdbc:sqlite:${user.dir}/sqlite-data/mingshu.db?journal_mode=WAL&busy_timeout=5000`
5. 杀 PID 21712（H2 残留进程占 jar）
6. 启动验证 35 张表 + WAL 模式生效
7. 清理 `h2data/`（备份已留）
8. commit `35d8555`

**踩坑 4 次**：
- ❌ `@Configuration` 构造器未触发
- ❌ `@Component` + `ApplicationListener` 也未触发
- ❌ `spring.factories` 在 jar 顶层 META-INF/ 不在 classpath
- ✅ 最终方案：`BeanFactoryPostProcessor` + `EnvironmentAware`

### 4.2 MPDM（皓元）P0-2-A 实战

**4 步优化**：

1. 备份 `data/` → `_backups-2026-08-30/mpdm-sqlite-backup-*.tar.gz`（105MB）
2. 改 `app/database.py`：加 `@event.listens_for(engine, "connect")`
3. 重启 + 验证 6 项 PRAGMA 全生效
4. commit `611121a`

**关键发现**：
- ✅ 连接级 PRAGMA 业务连接走的是正确的 NORMAL/cache_size=-64000/temp_store=MEMORY
- ⚠️ 但 **DB 全局默认 PRAGMA 未改**（监控 sqlite3 直连读 DB 全局默认会显示错误值）
- 🆘 **待办**：补一行 `PRAGMA journal_mode=WAL` 持久化（或者连接 PRAGMA 加 `PRAGMA journal_mode=WAL` 的副作用会持久化 DB 全局默认）

---

## 五、监控工具

### 5.1 健康检查脚本（`凌瑶/scripts/sqlite_health_check.py`）

```bash
# MPDM
python3 凌瑶/scripts/sqlite_health_check.py --project mpd

# POR-M
python3 凌瑶/scripts/sqlite_health_check.py --project porm

# 任意路径
python3 凌瑶/scripts/sqlite_health_check.py /path/to/any.db

# JSON 输出
python3 凌瑶/scripts/sqlite_health_check.py --project mpd --json
```

**退出码**：
- 0 = 全绿
- 1 = 有黄（如连接数 > 10、WAL > 50MB）
- 2 = 有红（如文件不存在、PRAGMA 错误、父目录缺失）

### 5.2 MPDM WAL 大小 + 慢查询监控（`MPD-myself/scripts/db_monitor.py`）

每 5 分钟探测：
- DB 大小 / WAL 大小 / 活跃连接数
- 慢查询数（> 5/s 告警）
- 输出 JSON 到 `/tmp/mpdm-db-monitor.json`
- daemon PID 文件 `/tmp/mpdm-db-monitor.pid`

---

## 六、反模式（避坑 6 项）

### ❌ 6.1 不开 WAL
**症状**：高并发读写时频繁「database is locked」
**修正**：`PRAGMA journal_mode=WAL`

### ❌ 6.2 busy_timeout 留默认 0
**症状**：偶发锁冲突立即失败，业务中断
**修正**：`PRAGMA busy_timeout=5000`

### ❌ 6.3 synchronous 留默认 FULL
**症状**：WAL 模式下写慢 2-3 倍
**修正**：`PRAGMA synchronous=NORMAL`（WAL 下安全）

### ❌ 6.4 cache_size 留默认 2000（约 2MB）
**症状**：频繁磁盘 IO，查询慢
**修正**：`PRAGMA cache_size=-64000`（64MB）

### ❌ 6.5 SQLite JDBC 不创建父目录
**症状**：启动报 `path to '...': '...' does not exist`
**修正**：Java 用 `BeanFactoryPostProcessor` / Python 用 `os.makedirs(exist_ok=True)`

### ❌ 6.6 PRAGMA 只在直连时设，不在 ORM 连接时设
**症状**：监控 sqlite3 CLI 看 PRAGMA 都对，但业务连接用 SQLAlchemy 没生效
**修正**：必须用 event listener / BeanFactoryPostProcessor 在每个连接建立时设

---

## 七、性能基准（vs 默认）

| 操作 | 默认配置 | P0-2-A 配置 | 提升 |
|---|---|---|---|
| 单条 INSERT | ~50ms | ~15ms | **3.3x** |
| 批量 INSERT 1000 行 | ~5s | ~1.5s | **3.3x** |
| 并发读 + 写混合 | 频繁 lock | 几乎无锁 | **10x+** |
| 大查询（>1MB 结果）| 走磁盘 | 走 mmap | **5-10x** |
| 临时排序 | 走磁盘 | 走内存 | **2-5x** |

（数据基于 MPDM 395MB 数据库 + 58 表 + 高并发场景估算）

---

## 八、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版，POR-M P0-1 + MPDM P0-2-A 实战经验沉淀 |

---

## 九、参考文档

- `凌瑶/docs/db-selection-spec.md` · 凌瑶空间 DB 选型规范
- `凌瑶/docs/db-7-components-overview.md` · 7 组件全景图
- [SQLite 官方文档 - PRAGMA](https://www.sqlite.org/pragma.html)
- [SQLite WAL 模式](https://www.sqlite.org/wal.html)
- [Hibernate Community Dialects](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#database-dialect)
