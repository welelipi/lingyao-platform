# 凌瑶智数 · H2 → PostgreSQL 14 迁移计划

> 起草时间：2026-08-26 13:38
> 迁移原因：100 并发 + 多租户 + AI 特性 + 私有化场景
> 目标：CVM `118.195.197.15` 装 PostgreSQL 14，凌瑶 Spring Boot 直连

---

## 一、迁移目标

| 项目 | 迁移前 | 迁移后 |
|---|---|---|
| 数据库 | H2 file-based | **PostgreSQL 14** |
| 持久化路径 | `/opt/lingyao/data/lingyao.mv.db` | PostgreSQL 服务 `localhost:5432` |
| 备份脚本 | `cp lingyao.mv.db` | `pg_dump` + gzip |
| Spring Boot 配置 | `jdbc:h2:file:...` | `jdbc:postgresql://localhost:5432/lingyao` |
| 数据量 | ~4 个产品 + admin 用户 | 同上（保留所有数据） |
| 性能 | 写并发受限 | 行级锁，可撑 1000+ 并发 |

---

## 二、迁移阶段（4 大步）

### Step A · 安装 + 配置 PostgreSQL（CVM，30 分钟）

| 子步骤 | 内容 | CVM 命令 |
|---|---|---|
| A.1 | apt install postgresql-14 | `sudo apt update && sudo apt install -y postgresql` |
| A.2 | 启动 + 开机自启 | `sudo systemctl start postgresql && sudo systemctl enable postgresql` |
| A.3 | 创建数据库 + 用户 | `sudo -u postgres psql ...` |
| A.4 | 配监听（接受 localhost） | 修改 `postgresql.conf` + `pg_hba.conf` |
| A.5 | 验证 | `psql -U lingyao_app -d lingyao -h 127.0.0.1` |

### Step B · 后端代码改造（Mac 本地，1 小时）

| 子步骤 | 内容 |
|---|---|
| B.1 | `pom.xml` 加 `postgresql` JDBC 驱动 + Flyway 依赖 |
| B.2 | 写 `V1__init.sql`（H2 schema → PostgreSQL 语法转译） |
| B.3 | `application-private.yml` 改 datasource URL |
| B.4 | 关掉 `spring.jpa.hibernate.ddl-auto`（让 Flyway 接管）|
| B.5 | `mvn package` 重新打包 jar |

### Step C · 数据迁移（30 分钟）

| 子步骤 | 内容 |
|---|---|
| C.1 | 停 Spring Boot（H2 文件锁释放）|
| C.2 | 用 H2 `RUNSCRIPT` 把 4 个产品 + admin 用户导出为 SQL |
| C.3 | 在 PostgreSQL 里跑该 SQL |
| C.4 | 启动 Spring Boot → Flyway 自动建表 + 数据导入 |
| C.5 | 浏览器登录测试 + CRUD 测试 |

### Step D · 备份 + 监控切换（30 分钟）

| 子步骤 | 内容 |
|---|---|
| D.1 | 把 `backup-h2.sh` 改成 `backup-postgres.sh`（用 `pg_dump`）|
| D.2 | 改 cron |
| D.3 | 在 `monitor.sh` 加 PostgreSQL 连接检查 |

---

## 三、关键配置文件（提前看）

### 3.1 `/etc/postgresql/14/main/postgresql.conf`

```
listen_addresses = 'localhost'  # 只接受本地（安全）
port = 5432
max_connections = 100
shared_buffers = 1GB             # 4GB RAM 的一半给 PG
effective_cache_size = 2GB
work_mem = 4MB
maintenance_work_mem = 256MB
wal_buffers = 16MB
log_destination = 'stderr'
logging_collector = on
log_directory = 'log'
log_filename = 'postgresql-%Y%m%d.log'
log_rotation_age = 1d
log_rotation_size = 100MB
```

### 3.2 `/etc/postgresql/14/main/pg_hba.conf`

```
# 允许本地 lingyao_app 用户用密码连
host    lingyao    lingyao_app    127.0.0.1/32    md5
host    lingyao    lingyao_app    ::1/128         md5
```

### 3.3 `application-private.yml`（凌瑶 Spring Boot）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/lingyao
    username: lingyao_app
    password: <从 .env.private 读>
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway 接管，Hibernate 不再自动建表
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

## 四、回滚预案

如果 PostgreSQL 启动后凌瑶出问题：

1. `sudo systemctl stop postgresql`
2. 改 `application-private.yml` 回到 `jdbc:h2:file:...`
3. `mvn package` + 重启 jar
4. → 立刻回到 H2 模式，**不影响生产**

数据双写策略不在本次范围（避免复杂度）。

---

## 五、决策记录

| 日期 | 决策 | 原因 |
|---|---|---|
| 2026-08-26 | 用 PostgreSQL 14（不是 MySQL）| 多租户 + AI + 私有化 + 国产化迁移路径 |
| 2026-08-26 | Flyway 管理 schema，不用 Hibernate auto-DDL | 多 DB 兼容 + schema 审计 |
| 2026-08-26 | 保留 H2 作为备选 | 小客户/演示 |
| 2026-08-26 | 不上 MySQL 兼容 | 增加复杂度，国产化路径不通 |
| 2026-08-26 | 单实例 PostgreSQL（不集群）| 4GB RAM 100 并发够用 |

---

**主人一句话总结：H2 是 demo 数据库，PostgreSQL 是生产数据库。装上 PG 凌瑶才算真·生产级。**
