# 主仓侧 5 项稳定性 Fix 方案（远端代码库上传前必做）

> **状态**：起草版（未实施）· 主人在主人决策后才开始落地
> **写于**：2026-08-29 17:46
> **作者**：架构师 Agent（基于主人提出的"上传远端代码库后是否稳定"问题）
> **作用**：主仓单独 git clone + CVM 部署能跑起来，跳转 URL 不再硬编码 localhost

---

## 0. 风险清单回顾

| # | 风险 | 影响 | 严重度 |
|---|---|---|---|
| 1 | `sub_task.base_url` 硬编码 localhost | CVM 部署后用户浏览器解析 localhost=用户本机，跳不过去 | 🔴 P0 |
| 2 | daemon 脚本绝对路径 | 换机器/换用户直接挂 | 🔴 P0 |
| 3 | 4 个子产品独立仓不在主仓 | 主仓单独 clone 无法独立运行 | 🟡 P1（架构级，靠 README 缓解）|
| 4 | prod profile 缺文档 | 部署方不知道怎么切到 private profile | 🟡 P1 |
| 5 | SSO-CORS 没策略 | 主站跳子产品 cookie 行为未约定 | 🟡 P1 |

---

## Fix 1：base_url 配置外移（最重要）

### 1.1 目标
`sub_task.base_url` 当前在 `data.sql` 写死 localhost。改为从 `application.yml` 读，环境变量注入。这样：
- dev/local：`http://localhost:3100`（默认值）
- staging：`http://10.0.0.5:3100`（部署时注入环境变量）
- prod：`http://mingshu.lingyao.cn`（部署时注入环境变量）

### 1.2 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `backend/src/main/java/com/lingyao/platform/config/LingyaoSubTaskProperties.java` | `@ConfigurationProperties(prefix="lingyao.subtask")` 映射 Map |
| `backend/src/main/resources/application.yml` | 新增 `lingyao.subtask.routes.{geo,hpd,aidd,dinfo,porm}` 配置段 |
| `backend/src/main/java/com/lingyao/platform/controller/SubTaskController.java` | `enter()` 改读 `LingyaoSubTaskProperties` 而非 `sub_task.base_url` |
| `backend/src/main/resources/data.sql` | sub_task 表的 `base_url`、`health_url` 改为 `NULL`（或保留做 fallback） |
| `backend/src/main/java/com/lingyao/platform/LingyaoPlatformApplication.java` | 加 `@EnableConfigurationProperties(LingyaoSubTaskProperties.class)` |

### 1.3 代码模板

#### LingyaoSubTaskProperties.java（新文件）

```java
package com.lingyao.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子任务路由配置（取代 data.sql 里硬编码的 base_url）
 *
 * 用法：
 *   application.yml:
 *     lingyao:
 *       subtask:
 *         routes:
 *           geo:
 *             base-url: http://10.0.0.5:8090
 *             health-url: http://10.0.0.5:8090/api/health
 *             entry-path: /subtask/geo
 *
 *   环境变量覆盖：
 *     LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090
 */
@Configuration
@ConfigurationProperties(prefix = "lingyao.subtask")
public class LingyaoSubTaskProperties {

    private Map<String, Route> routes = new LinkedHashMap<>();

    public Map<String, Route> getRoutes() { return routes; }
    public void setRoutes(Map<String, Route> routes) { this.routes = routes; }

    public static class Route {
        private String baseUrl;
        private String healthUrl;
        private String entryPath;
        // getter/setter
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getHealthUrl() { return healthUrl; }
        public void setHealthUrl(String healthUrl) { this.healthUrl = healthUrl; }
        public String getEntryPath() { return entryPath; }
        public void setEntryPath(String entryPath) { this.entryPath = entryPath; }
    }
}
```

#### application.yml 新增段

```yaml
lingyao:
  # ... 原有 jwt/release 段保留 ...
  
  # 子任务路由（取代 data.sql 里的硬编码 base_url）
  subtask:
    routes:
      geo:
        base-url: ${LINGYAO_SUBTASK_GEO_BASE_URL:http://127.0.0.1:8090}
        health-url: ${LINGYAO_SUBTASK_GEO_HEALTH_URL:http://127.0.0.1:8090/api/health}
        entry-path: ${LINGYAO_SUBTASK_GEO_ENTRY_PATH:/subtask/geo}
      hpd:
        base-url: ${LINGYAO_SUBTASK_HPD_BASE_URL:http://localhost:3100}
        health-url: ${LINGYAO_SUBTASK_HPD_HEALTH_URL:http://localhost:8100/api/health}
        entry-path: ${LINGYAO_SUBTASK_HPD_ENTRY_PATH:/subtask/hpd}
      aidd:
        base-url: ${LINGYAO_SUBTASK_AIDD_BASE_URL:http://localhost:13000}
        health-url: ${LINGYAO_SUBTASK_AIDD_HEALTH_URL:http://localhost:18080/api/_diag/version}
        entry-path: ${LINGYAO_SUBTASK_AIDD_ENTRY_PATH:/subtask/aidd}
      dinfo:
        base-url: ${LINGYAO_SUBTASK_DINFO_BASE_URL:http://localhost:5181}
        health-url: ${LINGYAO_SUBTASK_DINFO_HEALTH_URL:http://localhost:8281/api/_diag/version}
        entry-path: ${LINGYAO_SUBTASK_DINFO_ENTRY_PATH:/subtask/dinfo}
      porm:
        base-url: ${LINGYAO_SUBTASK_PORM_BASE_URL:http://localhost:3190}
        health-url: ${LINGYAO_SUBTASK_PORM_HEALTH_URL:http://localhost:8280/api/_diag/version}
        entry-path: ${LINGYAO_SUBTASK_PORM_ENTRY_PATH:/subtask/porm}
```

#### SubTaskController.enter() 关键改动

```java
@Autowired private LingyaoSubTaskProperties subTaskProperties;  // 新增

@GetMapping("/{code}/enter")
public ApiResponse<?> enter(@PathVariable String code, HttpServletRequest request) {
    // ... 权限校验保留 ...
    
    LingyaoSubTaskProperties.Route route = subTaskProperties.getRoutes().get(code.toLowerCase());
    
    if (route == null || route.getBaseUrl() == null || route.getBaseUrl().isEmpty()) {
        // 路由未配置：返回降级（让前端跳 entry_path 占位）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ROUTING_NOT_READY");
        data.put("message", "子任务路由未配置（lingyao.subtask.routes." + code.toLowerCase() + ".base-url）");
        data.put("fallbackPath", route != null ? route.getEntryPath() : "/subtask/" + code.toLowerCase());
        return ApiResponse.ok("子任务路由未配置", data);
    }
    
    // ... 用 route.getBaseUrl() 代替 task.getBaseUrl() ...
    String url = route.getBaseUrl();
    // 拼接 5 参数 + platform_token（原有逻辑保留）
}
```

#### data.sql 改动

```sql
-- 保留 sub_task 表结构但 base_url/health_url 改为 NULL（由 application.yml 接管）
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'ACTIVE', 'GEO 品牌监测核心引擎', NULL, NULL, NOW(), NOW()),
  (2, 2, 'HPD 医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'ACTIVE', 'HPD 智能医院潜力预测（指向 MPD-myself 独立站）', NULL, NULL, NOW(), NOW()),
  -- ...
```

### 1.4 实施步骤

1. 新建 `LingyaoSubTaskProperties.java`
2. 改 `application.yml` 加配置段
3. `LingyaoPlatformApplication.java` 加 `@EnableConfigurationProperties`
4. 改 `SubTaskController.java` 注入新配置类 + 改读配置
5. 改 `data.sql` 把 base_url 改 NULL
6. `mvn package` 重新打包
7. **验证**：
   ```bash
   # dev 默认走 localhost
   curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:9091/api/sub/geo/enter | jq -r '.data.redirectUrl'
   # 期望：http://127.0.0.1:8090?...5 参数
   
   # prod 注入环境变量
   LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090 \
     java -jar lingyao-platform.jar --spring.profiles.active=private
   # 重启后 curl 期望：http://10.0.0.5:8090?...5 参数
   ```

### 1.5 风险提示
- ⚠️ 旧 `sub_task.base_url` 字段保留但不用，需前端/文档不再依赖该字段
- ⚠️ CORS：子产品 FastAPI 需在 CORS 白名单加主站域名（参见 Fix 5）

---

## Fix 2：守护进程脚本路径参数化

### 2.1 目标
3 个 `start_*_daemon.py` 当前写死 `/Users/hua/Documents/myself/凌瑶/...`，改为环境变量 `LINGYAO_HOME` 注入。

### 2.2 改动清单

| 文件 | 改动 |
|---|---|
| `scripts/start_backend_daemon.py` | 顶部加 `BASE_DIR = os.environ.get("LINGYAO_HOME", "...")` |
| `scripts/start_frontend_daemon.py` | 同上 |
| `scripts/start_dev_daemon.py` | 同上 |

### 2.3 代码模板

#### start_backend_daemon.py 顶部

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
凌瑶主仓后端守护进程启动器（路径参数化版）

环境变量：
  LINGYAO_HOME  - 主仓根目录（默认取脚本所在目录的父目录）
  LINGYAO_PROFILE - Spring profile（默认 dev）

示例：
  LINGYAO_HOME=/opt/lingyao LINGYAO_PROFILE=private \\
    python3 start_backend_daemon.py
"""

import os
import sys
import time
import signal
import subprocess

# === 路径参数化（铁律：永远不硬编码绝对路径）===
BASE_DIR = os.environ.get(
    "LINGYAO_HOME",
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # 默认脚本所在父目录
)
BACKEND_DIR = os.path.join(BASE_DIR, "backend")
JAR_PATH = os.path.join(BACKEND_DIR, "target", "lingyao-platform.jar")
LOG_DIR = os.path.join(BASE_DIR, "logs")
PID_FILE = os.path.join(BACKEND_DIR, "dev-backend.pid")
SPRING_PROFILE = os.environ.get("LINGYAO_PROFILE", "dev")

# ... 其余逻辑不变，把硬编码路径全部替换为上述变量 ...
```

#### start_frontend_daemon.py 顶部

```python
BASE_DIR = os.environ.get(
    "LINGYAO_HOME",
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
FRONTEND_DIR = os.path.join(BASE_DIR, "website")
LOG_FILE = os.path.join(BASE_DIR, "logs", "frontend.log")
PID_FILE = os.path.join(BASE_DIR, "website", "dev-frontend.pid")
FRONTEND_PORT = int(os.environ.get("LINGYAO_FRONTEND_PORT", "8765"))
```

### 2.4 部署示例

```bash
# CVM /opt/lingyao 部署
export LINGYAO_HOME=/opt/lingyao
export LINGYAO_PROFILE=private
export LINGYAO_FRONTEND_PORT=80

python3 /opt/lingyao/scripts/start_backend_daemon.py
python3 /opt/lingyao/scripts/start_frontend_daemon.py
```

### 2.5 验证
- 本机（不设环境变量）默认仍走 `/Users/hua/Documents/myself/凌瑶/...`（兼容旧行为）
- CVM 设 `LINGYAO_HOME=/opt/lingyao` 后用新路径

---

## Fix 3：README 升级

### 3.1 目标
当前 README.md 停留在 2026-08-11 的"products 符号链接"模型。需要补：
- 4 个子产品独立仓的位置（GEOM 在 `./geom/`，其他是 sibling 仓）
- 跨仓步骤（主仓单独 clone 后怎么跑）
- 5 项 fix 引用本文档

### 3.2 改动清单

| 文件 | 改动 |
|---|---|
| `README.md` | 重写"WorkBuddy 层级" + "SSO 协议" + "当前进度" 三个段 |

### 3.3 模板

```markdown
## 产品矩阵（5 个模块 · 跨仓架构）

| # | 模块 | 代码位置 | 端口 |
|---|---|---|---|
| 1 | **凌瑶主控台** | 本仓库 | 8765 (FE) / 9091 (BE) |
| 2 | **GEO 智策**（品牌优化）| `./geom/`（subtree）| 5180 (FE) / 8090 (BE) |
| 3 | **AIDD Copilot**（研发反馈）| `../AIDD/ai-project-copilot/`（sibling 仓）| 13000 (FE) / 18080 (BE) |
| 4 | **辰录 Dinfo**（填报系统）| `../Dinfo/`（sibling 仓）| 5181 (FE) / 8281 (BE) |
| 5 | **明枢 PORM**（协作平台）| `../PORM/`（sibling 仓，源头 GS-CoLab）| 3190 (FE) / 8280 (BE) |
| 6 | **皓元 HPD**（医院潜力预测）| `../MPD-myself/`（sibling 仓）| 3100 (FE) / 8100 (BE) |

## 跨仓运行步骤（远端代码库）

主仓单独 clone 无法独立运行，需要 4 个 sibling 仓配合：

```bash
# 1. 准备目录
mkdir -p ~/lingyao-workspace
cd ~/lingyao-workspace

# 2. clone 主仓
git clone https://github.com/<owner>/lingyao.git
cd lingyao
git submodule update --init --recursive  # GEOM 在 ./geom/

# 3. clone 4 个 sibling 仓（必须放在主仓同级目录）
cd ..
git clone https://github.com/<owner>/AIDD.git
git clone https://github.com/<owner>/Dinfo.git
git clone https://github.com/<owner>/PORM.git
git clone https://github.com/<owner>/MPD-myself.git

# 4. 启动
cd lingyao
export LINGYAO_HOME=$(pwd)
export LINGYAO_PROFILE=private  # prod 模式
python3 scripts/start_backend_daemon.py
python3 scripts/start_frontend_daemon.py

# 5. 子产品各自启动
(cd ../AIDD/ai-project-copilot && python3 start_backend_daemon.py)
(cd ../Dinfo/backend && python3 start_dept_fill_daemon.py)
(cd ../PORM/backend && python3 start_porm_daemon.py)
(cd ../MPD-myself/backend && python3 start_mpd_daemon.py)
```

## SSO 跳转协议（V2.0.10 起）

主网站登录后跳子产品：

```
{base_url}/#/sso/callback
  ?tenant_id={company_id}
  &user_id={user_id}
  &user={username}
  &display_name={display_name}
  &platform_token={Bearer JWT}
```

base_url 配置见 `application.yml` 的 `lingyao.subtask.routes.{geo,hpd,aidd,dinfo,porm}.base-url`。
环境变量覆盖：`LINGYAO_SUBTASK_<PRODUCT>_BASE_URL`。

详见 [`docs/portal-sso-design/`](./portal-sso-design/)：
- `01-main-repo-stability-fixes.md` — 主仓稳定性 5 项 fix
- `02-sub-product-sso-adaptation.md` — 4 个子产品 SSO 改造方案
```

---

## Fix 4：CVM 部署文档化

### 4.1 目标
`deploy/cvm/deploy-prod.sh` 当前是脚本骨架，需要补：
- JDK 21 + Maven + Node + npm 依赖说明
- dev vs private profile 差异
- 数据库差异（H2 内存库 vs PostgreSQL）
- 环境变量清单

### 4.2 改动清单

| 文件 | 改动 |
|---|---|
| `deploy/cvm/deploy-prod.sh` | 加注释 + README 引用 |
| **新增** `deploy/cvm/DEPLOYMENT.md` | 完整部署手册 |

### 4.3 deploy-prod.sh 注释模板

```bash
#!/usr/bin/env bash
# ============================================
# 凌瑶智数 · CVM 生产部署脚本
# ============================================
# 前置依赖：
#   - JDK 21（apt install openjdk-21-jdk-headless）
#   - Maven 3.8+（mvn --version）
#   - Node 22 LTS + npm（nvm install 22）
#   - PostgreSQL 14+（apt install postgresql）
#   - Nginx（apt install nginx）
#
# 关键环境变量：
#   LINGYAO_HOME          主仓根目录（默认 /opt/lingyao）
#   LINGYAO_PROFILE       Spring profile（prod 用 private）
#   LINGYAO_JWT_SECRET    JWT 签名密钥（部署时随机生成，32+ 字符）
#   LINGYAO_DB_PASSWORD   PostgreSQL 密码
#   LINGYAO_SUBTASK_<X>_BASE_URL  子产品 URL（详见 application.yml）
#
# Profile 差异：
#   dev     H2 内存库 + ddl-auto=create-drop + H2 Console（仅本地开发）
#   private PostgreSQL + ddl-auto=update（生产用，数据持久化）
#
# 子产品独立部署（详见 docs/portal-sso-design/02-sub-product-sso-adaptation.md）
# ============================================
```

### 4.4 DEPLOYMENT.md 模板

```markdown
# 凌瑶智数 · CVM 生产部署手册

## 1. 环境要求

| 软件 | 版本 | 安装命令 |
|---|---|---|
| JDK | 21 | `apt install openjdk-21-jdk-headless` |
| Maven | 3.8+ | `apt install maven` |
| Node | 22 LTS | `nvm install 22 && nvm use 22` |
| PostgreSQL | 14+ | `apt install postgresql-14` |
| Nginx | 1.18+ | `apt install nginx` |

## 2. 首次部署

```bash
# 1. 拉代码
mkdir -p /opt/lingyao
cd /opt/lingyao
git clone https://github.com/<owner>/lingyao.git .
git submodule update --init --recursive

# 2. 生成 JWT 密钥
export LINGYAO_JWT_SECRET=$(openssl rand -base64 48)

# 3. 配置 PostgreSQL
sudo -u postgres psql <<EOF
CREATE DATABASE lingyao;
CREATE USER lingyao WITH PASSWORD '<LINGYAO_DB_PASSWORD>';
GRANT ALL PRIVILEGES ON DATABASE lingyao TO lingyao;
EOF

# 4. 配置 application-private.yml（不进 git）
cat > /opt/lingyao/backend/src/main/resources/application-private.yml <<EOF
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/lingyao
    username: lingyao
    password: \${LINGYAO_DB_PASSWORD}
lingyao:
  jwt:
    secret: \${LINGYAO_JWT_SECRET}
  subtask:
    routes:
      geo:
        base-url: http://10.0.0.5:8090
      hpd:
        base-url: http://10.0.0.6:3100
      # ...
EOF

# 5. 打包
cd /opt/lingyao/backend
mvn clean package -DskipTests

# 6. 启动
cd /opt/lingyao
export LINGYAO_PROFILE=private
python3 scripts/start_backend_daemon.py
python3 scripts/start_frontend_daemon.py
```

## 3. 后续更新

```bash
cd /opt/lingyao
git pull
cd backend && mvn clean package -DskipTests
cd ..
python3 scripts/start_backend_daemon.py restart  # 守护进程自重启
```

## 4. 故障排查

- 后端起不来：`tail -100 logs/app.out` 看启动日志
- 子产品跳不过去：`curl http://localhost:9091/api/sub/geo/enter` 看 base_url
- 跨域 cookie 不通：参考 `docs/portal-sso-design/02-sub-product-sso-adaptation.md`
```

---

## Fix 5：SSO-CORS 文档化

### 5.1 目标
主站 ↔ 子产品跨域 cookie 行为未约定。文档化主子产品同 `.lingyao.cn` 子域共享 cookie 策略。

### 5.2 改动清单

| 文件 | 改动 |
|---|---|
| **新增** `凌瑶/docs/portal-sso-design/03-sso-cors-policy.md` | 完整 CORS 策略 |

### 5.3 模板

```markdown
# SSO-CORS 策略文档

## 1. 域名规划（生产环境）

| 角色 | 域名 |
|---|---|
| 主站 | `lingyao.cn` |
| 子产品 HPD | `hpd.lingyao.cn` |
| 子产品 AIDD | `aidd.lingyao.cn` |
| 子产品 Dinfo | `dinfo.lingyao.cn` |
| 子产品 PORM | `mingshu.lingyao.cn` |
| 子产品 GEOM | `prism.lingyao.cn` |

## 2. Cookie 策略

主站登录后写入 JWT cookie：
```
Set-Cookie: lingyao_token=<JWT>; Domain=.lingyao.cn; Path=/; HttpOnly; Secure; SameSite=Lax
```

子产品 `api/sso/login` 写入本地 session cookie：
```
Set-Cookie: <product>_session=<session>; Domain=.lingyao.cn; Path=/; HttpOnly; Secure; SameSite=Lax
```

## 3. CORS 白名单（子产品 FastAPI）

每个子产品的 `main.py` CORS 配置：
```python
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "https://lingyao.cn",
        "https://hpd.lingyao.cn",  # 自己
        "https://aidd.lingyao.cn",  # 其他子产品（跨产品跳转场景）
        # ...
    ],
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization"],
)
```

## 4. SSO 跳转流程

```
用户浏览器
   │
   ├─→ GET https://lingyao.cn/portal.html
   │   (本地 cookie: lingyao_token)
   │
   ├─→ POST https://lingyao.cn/api/sub/hpd/enter
   │   (Authorization: Bearer <lingyao_token>)
   │   ← {"redirectUrl":"https://hpd.lingyao.cn/#/sso/callback?platform_token=..."}
   │
   ├─→ GET https://hpd.lingyao.cn/#/sso/callback?platform_token=...
   │   (浏览器加载 Vite SPA)
   │
   ├─→ GET https://hpd.lingyao.cn/api/sso/login?token=<platform_token>
   │   ← 302 Redirect, Set-Cookie: hpd_session=<>; Domain=.lingyao.cn
   │
   └─→ GET https://hpd.lingyao.cn/dashboard
       (Cookie: hpd_session 自动带上)
```

## 5. 开发环境兼容

dev/local：
- 主站：`http://localhost:8765` 或 `http://127.0.0.1:8765`
- 子产品：`http://localhost:3100` 等
- cookie `Domain=localhost` 不跨子域工作（localhost 没有 `.localhost` 子域概念）
- 解决：dev 模式不设 cookie domain，依赖 `SameSite=Lax` + 同站跳转

---

## 6. 总结

| 改动 | 工作量 | 风险 |
|---|---|---|
| Fix 1 base_url 配置外移 | 0.3 人日 | 低（破坏性改动，需重 mvn package）|
| Fix 2 daemon 路径参数化 | 0.1 人日 | 极低（向后兼容，不设环境变量走默认）|
| Fix 3 README 升级 | 0.1 人日 | 无 |
| Fix 4 CVM 部署文档化 | 0.2 人日 | 无 |
| Fix 5 SSO-CORS 文档化 | 0.1 人日 | 无 |
| **合计** | **0.8 人日** | |

预计可在 1 个工作日内全部完成。
```

---

## 实施顺序建议

1. **先做 Fix 2 + Fix 5**（最简单，0.2 人日）
2. **再做 Fix 1**（最重要，0.3 人日）
3. **最后做 Fix 3 + Fix 4**（文档化，0.3 人日）

**总投入 0.8 人日**，可在主仓上传远端代码库前一次性完成。
