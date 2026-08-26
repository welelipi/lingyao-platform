#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════
# 凌瑶智数 · Docker 一键启动脚本（私有化客户友好）
# ────────────────────────────────────────────────────────────
# 流程：
#   1. 检查 docker / docker-compose
#   2. 创建数据/日志/备份目录
#   3. 构建镜像（首次需 3-5 分钟）
#   4. 启动容器
#   5. 等待健康检查通过
#   6. 打印初始登录信息
#
# 用法：
#   bash bootstrap-docker.sh                # 一键启动（Docker Hub 镜像）
#   bash bootstrap-docker.sh --cn-mirror    # 用阿里云镜像（解决 Hub 拉取慢）
#   bash bootstrap-docker.sh --reset        # 清空数据重新初始化
#   bash bootstrap-docker.sh --cn-mirror --reset
# ════════════════════════════════════════════════════════════

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

RESET_FLAG=false
CN_MIRROR=false
for arg in "$@"; do
  case "$arg" in
    --reset)  RESET_FLAG=true ;;
    --cn-mirror) CN_MIRROR=true ;;
  esac
done

printf '\n\033[1;34m═══════════════════════════════════════════════════════════\033[0m\n'
printf '\033[1;34m  凌瑶智数 · Docker 一键部署\033[0m\n'
printf '\033[1;34m═══════════════════════════════════════════════════════════\033[0m\n\n'

# ─── 检查环境 ────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  echo -e '\033[0;31m[错误]\033[0m 未检测到 docker，请先安装 Docker Desktop / Docker Engine'
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo -e '\033[0;31m[错误]\033[0m 未检测到 docker compose（v2+），请升级 Docker'
  exit 1
fi

# ─── 准备目录 ────────────────────────────────
mkdir -p data logs backup
if [ "$RESET_FLAG" = true ]; then
  echo -e '\033[1;33m[reset]\033[0m 清空数据目录（首次登录密码将回到 admin123）'
  rm -rf data/*
fi

# ─── 选择 Dockerfile ──────────────────────────
if [ "$CN_MIRROR" = true ]; then
  DOCKERFILE="docker/Dockerfile.cn"
  echo -e '\033[0;34m[1/3]\033[0m 使用阿里云镜像源构建（解决 Docker Hub 拉取慢）...'
else
  DOCKERFILE="Dockerfile"
  echo -e '\033[0;34m[1/3]\033[0m 构建 Docker 镜像（首次约 3-5 分钟）...'
fi
docker build -f "$DOCKERFILE" -t lingyao/platform:1.0.0 .

echo -e '\n\033[0;34m[2/3]\033[0m 启动容器...'
docker compose up -d

echo -e '\n\033[0;34m[3/3]\033[0m 等待健康检查（最多 60 秒）...'
ATTEMPTS=0
MAX_ATTEMPTS=20
until [ $ATTEMPTS -ge $MAX_ATTEMPTS ]; do
  if curl -fsS --noproxy '*' http://127.0.0.1:9091/api/health > /dev/null 2>&1; then
    echo -e '\033[0;32m✓\033[0m 健康检查通过'
    break
  fi
  ATTEMPTS=$((ATTEMPTS+1))
  printf '.'
  sleep 3
done

if [ $ATTEMPTS -ge $MAX_ATTEMPTS ]; then
  echo -e '\n\033[0;31m[警告]\033[0m 健康检查超时，请运行 docker compose logs 查看'
fi

# ─── 输出登录信息 ─────────────────────────────
cat <<EOF

\033[1;32m═══════════════════════════════════════════════════════════\033[0m
\033[1;32m  凌瑶智数部署成功！\033[0m
\033[1;32m═══════════════════════════════════════════════════════════\033[0m

  访问地址：  http://127.0.0.1:9091
  超管账号：  admin
  初始密码：  admin123（首次登录强制改密）

  工作台：    http://127.0.0.1:9091/portal.html
  超管后台：  http://127.0.0.1:9091/admin/
  邀请注册页：http://127.0.0.1:9091/invite.html
  H2 控制台：http://127.0.0.1:9091/h2-console

  数据目录：  $(pwd)/data
  日志目录：  $(pwd)/logs
  备份目录：  $(pwd)/backup

  常用命令：
    docker compose ps                          # 查看状态
    docker compose logs -f                     # 查看日志
    docker compose down                        # 停止
    bash bootstrap-docker.sh --reset           # 清空数据重启
    bash bootstrap-docker.sh --cn-mirror       # 阿里云镜像构建
    docker exec lingyao-platform /app/backup.sh   # 手动备份

EOF