# 凌瑶智数 · Docker 部署指南

> 私有化客户友好：一键启动、持久化数据、自动备份、健康检查、可视化日志。

---

## 🚀 快速启动（推荐）

```bash
# 在 backend/ 目录下执行
bash bootstrap-docker.sh
```

脚本会自动：
1. 检查 Docker / Docker Compose 环境
2. 构建镜像（首次 3-5 分钟）
3. 启动容器（端口 9091）
4. 等待健康检查通过
5. 打印登录信息

启动成功后访问：
- **主站**：http://127.0.0.1:9091
- **工作台**：http://127.0.0.1:9091/portal.html
- **超管后台**：http://127.0.0.1:9091/admin/
- **H2 控制台**：http://127.0.0.1:9091/h2-console

初始账号：`admin / admin123`（**首次登录强制改密**）

---

## 📦 文件结构

```
backend/
├── Dockerfile                 # 标准构建（Docker Hub 官方镜像）
├── docker-compose.yml         # 编排：应用 + 数据卷 + 健康检查
├── bootstrap-docker.sh        # 一键启动脚本（推荐）
├── .dockerignore              # 构建排除文件
└── docker/
    ├── Dockerfile.cn          # 国内镜像源构建（解决 Hub 拉取超时）
    ├── entrypoint.sh          # 容器入口：自动生成 JWT 密钥
    └── backup.sh              # H2 数据库热备份脚本
```

---

## 🔧 手动启动（高级用户）

### 1. 构建镜像

```bash
# 标准版（Docker Hub）
docker build -t lingyao/platform:1.0.0 .

# 国内版（阿里云镜像源）
docker build -f docker/Dockerfile.cn -t lingyao/platform:1.0.0 .
```

### 2. 启动容器

```bash
# 准备数据/日志/备份目录
mkdir -p data logs backup

# 启动
docker run -d \
  --name lingyao-platform \
  -p 9091:9091 \
  -v $(pwd)/data:/app/data \
  -v $(pwd)/logs:/app/logs \
  -v $(pwd)/backup:/app/backup \
  -e LINGYAO_JWT_SECRET="$(openssl rand -base64 48)" \
  lingyao/platform:1.0.0

# 或用 docker-compose
docker-compose up -d
```

### 3. 查看状态

```bash
docker compose ps              # 容器状态
docker compose logs -f         # 实时日志
docker compose logs --tail=200 # 最近 200 行日志
```

---

## 💾 数据持久化

| 路径 | 内容 | 备份建议 |
|---|---|---|
| `data/lingyao.mv.db` | H2 主库 | 必须 |
| `data/.jwt_secret` | JWT 签名密钥 | 必须 |
| `logs/` | Spring Boot 运行日志 | 选备 |
| `backup/` | 自动备份产物 | — |

**容器内自动生成 JWT 密钥**：首次启动若未设 `LINGYAO_JWT_SECRET` 环境变量，`entrypoint.sh` 会自动生成并写入 `data/.jwt_secret`。**重启后保留同一密钥，旧 token 仍然有效**。

**强制全部用户重新登录**：删除 `data/.jwt_secret` 文件并重启容器。

---

## 🛡️ 数据备份

### 手动备份

```bash
docker exec lingyao-platform /app/backup.sh
```

产物：`backup/lingyao_YYYYMMDD_HHMMSS.zip`（含 H2 文件 + 元数据）

### 自动备份（cron）

```bash
# 每天凌晨 2 点备份
0 2 * * * cd /path/to/backend && docker exec lingyao-platform /app/backup.sh >> backup/cron.log 2>&1
```

容器内 `backup.sh` 默认保留最近 30 天的备份。

### 恢复备份

```bash
# 1. 停容器
docker compose down

# 2. 解压备份
cd data
unzip ../backup/lingyao_20260812_020000.zip

# 3. 删除 JWT 密钥（强制重新登录，避免密钥不一致）
rm -f .jwt_secret

# 4. 重启
cd .. && docker compose up -d
```

---

## 🌐 国内镜像源（解决拉取超时）

如果构建时遇到 `failed to do request: Head ... deadline exceeded`：

**方案 A**：使用国内版 Dockerfile
```bash
docker build -f docker/Dockerfile.cn -t lingyao/platform:1.0.0 .
```

**方案 B**：配置 Docker daemon 代理
```bash
# ~/.docker/config.json
{
  "registry-mirrors": ["https://docker.mirrors.ustc.edu.cn"]
}
```

---

## 🔌 常用命令速查

| 命令 | 作用 |
|---|---|
| `bash bootstrap-docker.sh` | 一键启动 |
| `bash bootstrap-docker.sh --reset` | 清空数据重启（密码回 admin123） |
| `docker compose ps` | 查看容器状态 |
| `docker compose logs -f` | 实时日志 |
| `docker compose down` | 停止容器 |
| `docker compose up -d` | 启动容器 |
| `docker exec lingyao-platform /app/backup.sh` | 手动备份 |
| `docker exec -it lingyao-platform /bin/bash` | 进入容器 |

---

## ⚙️ 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LINGYAO_JWT_SECRET` | 自动生成 | JWT 签名密钥（首次启动后持久化到 data/.jwt_secret） |
| `SPRING_PROFILES_ACTIVE` | `private` | Spring profile（私有化模式） |
| `JAVA_OPTS` | 见 Dockerfile | JVM 参数 |
| `TZ` | `Asia/Shanghai` | 时区 |

---

## ❓ 常见问题

**Q：健康检查超时？**
A：首次启动较慢（需初始化 H2 库 + 创建默认数据）。等待 60 秒后查看日志：`docker compose logs`。

**Q：忘记 admin 密码？**
A：`bash bootstrap-docker.sh --reset` 重置数据，密码回到 admin123。

**Q：JWT 密钥丢失怎么办？**
A：删除 `data/.jwt_secret` 并重启容器，会自动生成新密钥（所有用户需重新登录）。

**Q：如何升级到新版本？**
A：
```bash
docker compose down              # 停旧容器
docker build -t lingyao/platform:1.0.1 .  # 重建新镜像
docker compose up -d             # 启动新容器（数据卷不变）
```