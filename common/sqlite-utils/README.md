# LingYao SQLite Utils（v1.0 · 2026-08-30）

> **凌瑶空间通用 SQLite 工具** — Java + Spring Boot 模块直接复用
>
> **核心价值**：解决 SQLite JDBC 不会自动创建父目录的通用痛点（H2 会，SQLite 不会）

---

## 一、包含组件

| 类 | 作用 | 触发时机 |
|---|---|---|
| `SQLiteParentDirInitializer` | 解析 `spring.datasource.url`，自动创建 SQLite 数据库父目录 | `BeanFactoryPostProcessor`（Spring 最早阶段）|

---

## 二、使用方式

### 2.1 Maven 依赖

```xml
<dependency>
    <groupId>com.lingyao</groupId>
    <artifactId>sqlite-utils</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2.2 Spring Boot 配置（application-{profile}.yml）

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${user.dir}/sqlite-data/mingshu.db?journal_mode=WAL&busy_timeout=5000
    driver-class-name: org.sqlite.JDBC
    hibernate:
      dialect: org.hibernate.community.dialect.SQLiteDialect
```

### 2.3 自动注册

通过 `META-INF/spring.factories` 自动注册 `SQLiteParentDirInitializer`，
**无需** 在业务代码中 `@Import` 或 `@ComponentScan`。

启动时日志：
```
✅ Created SQLite parent dir: /path/to/sqlite-data
```

如果父目录已存在：
```
SQLite parent dir already exists: /path/to/sqlite-data
```

---

## 三、踩坑历史（P0-1 POR-M 实战 · 8 阶段迁移总结）

| # | 方案 | 结果 |
|---|---|---|
| 1 | `@Configuration` 构造器中创建父目录 | ❌ 不触发（lazy bean）|
| 2 | `@Component` + `ApplicationListener` | ❌ 不触发（lazy listener）|
| 3 | `spring.factories` 自动注册 | ✅ **唯一可行** |

**为什么 `BeanFactoryPostProcessor` 是最早的？**

Spring Bean 生命周期：
1. Environment 准备（profile/property 加载）
2. `BeanFactoryPostProcessor.postProcessBeanFactory()` ← **这一步最早**
3. `BeanPostProcessor.postProcessBeforeInitialization()`
4. Bean 构造 + `@PostConstruct`
5. `ApplicationListener.onApplicationEvent()`
6. `SmartInitializingSingleton.afterSingletonsInstantiated()`

我们的需求是「在 DataSource Bean 创建之前，先把父目录建好」，所以必须用 `BeanFactoryPostProcessor`。
`@Configuration` 构造器在第 4 步才触发，但 DataSource Bean 也在这时创建——race condition。

---

## 四、支持哪些 URL 格式

| URL | 解析结果 |
|---|---|
| `jdbc:sqlite:/abs/path/db.db` | 父目录 `/abs/path` |
| `jdbc:sqlite:relative/path/db.db` | 父目录 `relative/path`（相对 CWD）|
| `jdbc:sqlite:${user.dir}/sqlite-data/db.db?journal_mode=WAL&busy_timeout=5000` | 父目录 `${user.dir}/sqlite-data`（占位符自动解析）|
| `jdbc:sqlite::memory:` | 返回 null（不创建，内存数据库）|
| `jdbc:postgresql://...` | 返回 null（非 SQLite，跳过）|

---

## 五、验证（POR-M 实战）

### 5.1 启动日志

```
SQLiteParentDirInitializer: SQLite parent dir already exists: /Users/hua/.../sqlite-data
```

### 5.2 健康检查

```bash
python3 /Users/hua/Documents/myself/凌瑶/scripts/sqlite_health_check.py --project porm
```

### 5.3 表数验证

```bash
sqlite3 /path/to/mingshu.db ".tables" | wc -l
```

POR-M 验证结果：35 张表创建成功（WAL 模式生效）。

---

## 六、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0.0 | 2026-08-30 | 初版，从 POR-M P0-1 实战经验抽出公用工具类 |

---

## 七、参考文档

- `凌瑶/docs/sqlite-pragma-spec.md` §2（Spring Boot 模式 + 6 项 PRAGMA）
- `凌瑶/docs/db-selection-spec.md` §2.2（嵌入式 SQLite 轨）
- `明枢/DEPLOYMENT.md`（POR-M 实战部署文档）
- P0-1 commit `35d8555`（H2 → SQLite 迁移 8 阶段）
