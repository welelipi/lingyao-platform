# 凌瑶智数 · CVM 部署文档

> **适用场景**：腾讯云 CVM / 阿里云 ECS / 自建机房 Linux 服务器（CentOS / Ubuntu / Debian）的凌瑶智数主仓私有化部署。
>
> **部署前提**：JDK 21 + Maven 3.9+ + Node.js 22+（仅前端开发期需要）
>
> **文档版本**：V2.0.11（2026-08-29）· 配套 `deploy/cvm/` 目录下 4 个文件使用

---

## 一、部署架构总览

```
┌─────────────────────────────────────────────────────────┐
│  腾讯云 CVM (CentOS 7+ / Ubuntu 22+)                    │
│                                                          │
│  ┌──────────────────┐      ┌────────────────────────┐  │
│  │  Nginx :80       │      │  PostgreSQL :5432       │  │
│  │  (反向代理)       │      │  (prod 数据库)           │  │
│  │  nginx-lingyao.  │ ───→ │  lingyao                │  │
│  │  conf            │      │                         │  │
│  └──────────────────┘      └────────────────────────┘  │
│           ↑                                                │
│  ┌────────┴────────────────────┐                       │
│  │  Lingyao Backend :9091       │                       │
│  │  (Spring Boot + JDK 21)      │                       │
│  │  lingyao-backend.service     │                       │
│  └──────────────────────────────┘                       │
│           ↑                                                │
│  ┌────────┴────────────────────┐                       │
│  │  Lingyao Frontend :8765      │                       │
│  │  (Python http.server)        │                       │
│  │  start_frontend_daemon.py    │                       │
│  └──────────────────────────────┘                       │
└─────────────────────────────────────────────────────────┘
           ↑
           │ (公网访问)
           ↓
   ┌────────────────┐
   │  浏览器客户端    │
   │  (终端用户)      │
   └────────────────┘
```

> **公网入口**：备案通过后走域名（如 `www.lydmed.com`），备案审核中走 CVM 公网 IP（如 `http://118.195.197.15/`）。
> **本地入口**：`http://localhost:9091/` 或 `http://localhost/portal.html`（Nginx 反代）。

---

## 二、系统要求

### 2.1 操作系统

- ✅ CentOS 7.6+ / CentOS Stream 8+ / Rocky Linux 8+
- ✅ Ubuntu 20.04 LTS / 22.04 LTS / 24.04 LTS
- ✅ Debian 11 / 12
- ❌ macOS（开发机用，不上生产）
- ❌ Windows Server（不在本方案范围）

### 2.2 必需依赖

| 软件 | 版本 | 安装命令（apt）| 安装命令（yum）|
|---|---|---|---|
| **JDK 21** | OpenJDK 21 LTS | `apt install openjdk-21-jdk-headless` | `yum install java-21-openjdk-devel` |
| **Maven** | 3.9+ | `apt install maven` | `yum install maven` |
| **PostgreSQL** | 14+ | `apt install postgresql` | `yum install postgresql-server` |
| **Python** | 3.10+ | `apt install python3` | `yum install python3` |
| **Nginx** | 1.20+ | `apt install nginx` | `yum install nginx` |

> ⚠️ **JDK 21 铁律**：JDK 17 跑不动 V2.0.11+ 的 fat jar（Jackson 序列化差异）。必须 OpenJDK 21 LTS。

### 2.3 硬件最低配置

- **CPU**：2 核
- **内存**：4 GB（dev: 1 GB 即可）
- **磁盘**：40 GB 系统盘 + 100 GB 数据盘（备份用）
- **公网带宽**：5 Mbps（CVM 默认）

---

## 三、目录规划

```bash
# 主仓部署根目录
/opt/lingyao/
├── lingyao-platform.jar          # Spring Boot fat jar
├── application-private.yml       # private profile 配置（含 DB 连接）
├── logs/                         # 日志目录
│   ├── backend.out
│   └── frontend.out
├── data/                         # H2 数据库（dev profile 用）
│   └── lingyao.mv.db
└── scripts/                      # 守护进程启动器（可选，CVM 上通常用 systemd）

# 数据盘（备份）
/mnt/datadisk0/
└── backup/lingyao/
    └── lingyao-YYYYMMDD.mv.db    # H2 备份文件（保留 30 天）
```

> 💡 **部署目录可以不是 `/opt/lingyao`**——只要设置 `LINGYAO_HOME` 环境变量指向主仓根目录即可。

---

## 四、构建与部署

### 4.1 构建 Spring Boot fat jar（在开发机）

```bash
git clone https://github.com/your-org/lingyao.git
cd lingyao/backend
mvn clean package -DskipTests
# 产物：backend/target/lingyao-platform.jar（约 55 MB）
```

### 4.2 上传到 CVM

```bash
# 在开发机
scp backend/target/lingyao-platform.jar root@118.195.197.15:/opt/lingyao/

# 或者用 rsync 增量同步
rsync -avz --progress backend/target/lingyao-platform.jar \
  root@118.195.197.15:/opt/lingyao/
```

### 4.3 部署 application-private.yml

```bash
# 在 CVM 上
cat > /opt/lingyao/application-private.yml << 'EOF'
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/lingyao
    username: lingyao
    password: ${LINGYAO_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect

lingyao:
  jwt:
    secret: ${LINGYAO_JWT_SECRET}  # 必须与子产品共享（SSO 用）
  subtask:
    routes:
      geo:
        base-url: ${LINGYAO_SUBTASK_GEO_BASE_URL:http://10.0.0.5:8090}
        health-url: ${LINGYAO_SUBTASK_GEO_HEALTH_URL:http://10.0.0.5:8090/api/health}
      hpd:
        base-url: ${LINGYAO_SUBTASK_HPD_BASE_URL:http://10.0.0.5:3100}
      # ... 其他子产品同理
EOF
```

### 4.4 启动 systemd service

```bash
# 复制 service 文件
cp deploy/cvm/lingyao-backend.service /etc/systemd/system/

# 重新加载 systemd
systemctl daemon-reload

# 启动 + 开机自启
systemctl enable lingyao-backend
systemctl start lingyao-backend

# 验证
systemctl status lingyao-backend
curl http://127.0.0.1:9091/api/version
```

### 4.5 配置 Nginx

```bash
# 复制配置文件
cp deploy/cvm/nginx-lingyao.conf /etc/nginx/conf.d/lingyao.conf

# 测试配置
nginx -t

# 重启
systemctl restart nginx

# 验证
curl http://127.0.0.1/portal.html
```

### 4.6 配置 H2 备份（可选，prod 用 PostgreSQL 时跳过）

```bash
# 添加 cron 任务
crontab -e
# 每天凌晨 3 点备份
0 3 * * * /opt/lingyao/deploy/cvm/backup-h2.sh
```

---

## 五、Profile 差异（关键）

| Profile | 数据库 | 用途 | 启动命令 |
|---|---|---|---|
| **`dev`** | H2 内存库（`jdbc:h2:mem:lingyao`）| 开发机 | `python3 scripts/start_dev_daemon.py` |
| **`private`** | PostgreSQL / H2 文件库 | 私有化部署 | `java -jar lingyao-platform.jar --spring.profiles.active=private` |
| **`prod`** | PostgreSQL（高可用集群）| 公网 SaaS（未来） | `java -jar lingyao-platform.jar --spring.profiles.active=prod` |

### 5.1 dev profile（开发机）

- **特点**：H2 内存库 + data.sql 自动加载 + 默认账号 admin/admin123 + H2 console `:9091/h2-console`
- **重启即清空**：每次重启数据归零（适合开发）
- **不要上生产**

### 5.2 private profile（私有化部署）

- **特点**：PostgreSQL（生产） / H2 文件库（演示）
- **数据持久化**：重启不丢
- **首登强制改密**：`data-private.sql` 中 admin 设为 `passwordChanged=false`，首登必须改密
- **配置文件**：`application-private.yml`（部署时手动生成，**不进 git**）

### 5.3 prod profile（未来公网 SaaS）

- **特点**：PostgreSQL 高可用集群 + CDN + WAF
- **当前未启用**

---

## 六、5 个子产品对接（CVM 环境）

### 6.1 子产品部署位置

CVM 部署推荐方案：**主仓 + 5 子产品仓在同一台 CVM**（节省成本），端口分配：

| 子产品 | 前端端口 | 后端端口 | 推荐部署路径 |
|---|---|---|---|
| **GEOM** | 5180 | 8090 | `/opt/lingyao/geom/`（凌瑶子仓副本）|
| **HPD** | 3100 | 8100 | `/opt/mpd-myself/` |
| **AIDD** | 13000 | 18080 | `/opt/aidd-copilot/` |
| **Dinfo** | 5181 | 8281 | `/opt/dinfo/` |
| **PORM** | 3190 | 8280 | `/opt/mpd-myself/`（与 HPD 同仓不同端口）|

### 6.2 主仓配置子产品 URL

在 `application-private.yml` 里设置 `lingyao.subtask.routes`：

```yaml
lingyao:
  subtask:
    routes:
      geo:
        base-url: http://127.0.0.1:5180        # GEOM 前端
        health-url: http://127.0.0.1:8090/api/health
      hpd:
        base-url: http://127.0.0.1:3100
        health-url: http://127.0.0.1:8100/api/health
      aidd:
        base-url: http://127.0.0.1:13000
        health-url: http://127.0.0.1:18080/api/_diag/version
      dinfo:
        base-url: http://127.0.0.1:5181
        health-url: http://127.0.0.1:8281/api/_diag/version
      porm:
        base-url: http://127.0.0.1:3190
        health-url: http://127.0.0.1:8280/api/_diag/version
```

或者用环境变量：

```bash
export LINGYAO_SUBTASK_GEO_BASE_URL="http://127.0.0.1:5180"
export LINGYAO_SUBTASK_HPD_BASE_URL="http://127.0.0.1:3100"
# ... 其他 3 个
systemctl restart lingyao-backend
```

### 6.3 子产品 SSO 接收端

子产品**必须**实现：

1. **后端 FastAPI `/api/sso/login` 端点**（用 `LINGYAO_JWT_SECRET` 验签 + 设置本地 session）
2. **前端 URL 参数解析**（`platform_token` 调 `/api/sso/login`，成功后跳 `/dashboard`）
3. **CORS 白名单**加主站域名（如 `https://www.lydmed.com`）
4. **审计日志**记录 SSO 来源（`source='lingyao'`）

详细模板见 `docs/portal-sso-design/02-sub-product-sso-adaptation.md`。

---

## 七、运维 SOP

### 7.1 查日志

```bash
tail -f /opt/lingyao/logs/backend.out
journalctl -u lingyao-backend -f  # systemd 视角
```

### 7.2 重启服务

```bash
systemctl restart lingyao-backend
```

### 7.3 数据库备份恢复

```bash
# 备份（自动 cron）
ls -lh /mnt/datadisk0/backup/lingyao/

# 手动备份
/opt/lingyao/deploy/cvm/backup-h2.sh

# 恢复
systemctl stop lingyao-backend
cp /mnt/datadisk0/backup/lingyao/lingyao-20260829.mv.db /opt/lingyao/data/lingyao.mv.db
systemctl start lingyao-backend
```

### 7.4 子产品连通性测试

```bash
curl http://127.0.0.1:8090/api/health    # GEOM
curl http://127.0.0.1:8100/api/health    # HPD
curl http://127.0.0.1:18080/api/_diag/version    # AIDD
curl http://127.0.0.1:8281/api/_diag/version    # Dinfo
curl http://127.0.0.1:8280/api/_diag/version    # PORM
```

### 7.5 版本号对账

```bash
# 后端
curl http://127.0.0.1:9091/api/version

# 前端（看 Login 页底部）
curl -s http://127.0.0.1:8765/portal.html | grep -E "V[0-9]+\."
```

前后端版本号必须一致（铁律：前端 `src/version.ts` + 后端 `application.yml app.version`）。

---

## 八、ICP 备案与公网域名

- **当前状态**：备案审核中（提交于 2026-08-xx）
- **审核通过后**：改 `nginx-lingyao.conf` 的 `server_name www.lydmed.com lydmed.com;`
- **HTTPS**：通过 Let's Encrypt 申请证书

```bash
# 申请 Let's Encrypt 证书
apt install certbot python3-certbot-nginx
certbot --nginx -d www.lydmed.com -d lydmed.com
```

---

## 九、常见问题

### Q1: 启动失败报 "Unsupported class file major version 65"

**A**: JDK 版本太低，必须 JDK 21（JDK 17 的 class file major version 是 61）。

### Q2: H2 console 报 "Database not found"

**A**: dev profile 启动失败，检查 `application.yml` 的 `spring.datasource.url` 是否为 `jdbc:h2:mem:lingyao`。

### Q3: 子产品跳转 502 Bad Gateway

**A**: 检查 `application-private.yml` 里 `lingyao.subtask.routes.<code>.base-url` 是否指向正确端口；用 `curl` 验证子产品后端可达。

### Q4: SSO 跳转后子产品仍显示登录页

**A**: 子产品未实现 `/api/sso/login` 端点。详见 `docs/portal-sso-design/02-sub-product-sso-adaptation.md`。

---

## 十、参考文档

- **主仓稳定性方案**：`docs/portal-sso-design/01-main-repo-stability-fixes.md`
- **子产品 SSO 改造**：`docs/portal-sso-design/02-sub-product-sso-adaptation.md`
- **SSO-CORS 策略**：`docs/portal-sso-design/03-sso-cors-policy.md`
- **CHANGELOG**：`CHANGELOG.md`
- **README**：`README.md`

---

_文档版本 V2.0.11 · 2026-08-29 更新 · 配套主仓 V2.0.11 使用_