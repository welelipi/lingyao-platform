#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════
# 凌瑶智数 · Docker 容器入口脚本
# ────────────────────────────────────────────────────────────
# 职责：
#   1. 启动时若 LINGYAO_JWT_SECRET 未设置 → 自动生成（持久化到 /app/data/.jwt_secret）
#   2. 启动 Spring Boot（私有化 profile）
#   3. 接收 SIGTERM 优雅停机
#
# 说明：
#   - 第一次启动时会自动生成 JWT 密钥并写入 /app/data/.jwt_secret，
#     后续启动会读取同一文件（保证重启后旧 token 仍然有效）。
#   - 删除 .jwt_secret 文件可强制让所有用户重新登录。
# ════════════════════════════════════════════════════════════

set -e

SECRET_FILE="/app/data/.jwt_secret"
APP_JAR="/app/lingyao-platform.jar"

# 第一次启动生成密钥（写入数据卷，重启后保留）
if [ -z "${LINGYAO_JWT_SECRET:-}" ]; then
  if [ -f "$SECRET_FILE" ]; then
    echo "[entrypoint] 复用持久化 JWT 密钥（删除 $SECRET_FILE 可强制全部用户重新登录）"
    export LINGYAO_JWT_SECRET="$(cat "$SECRET_FILE")"
  else
    echo "[entrypoint] 首次启动：生成 JWT 密钥..."
    GENERATED=$(openssl rand -base64 48 | tr -d '\n')
    echo "$GENERATED" > "$SECRET_FILE"
    chmod 600 "$SECRET_FILE"
    export LINGYAO_JWT_SECRET="$GENERATED"
    echo "[entrypoint] JWT 密钥已持久化到 $SECRET_FILE"
  fi
fi

# 信号处理：优雅停机
shutdown() {
  echo "[entrypoint] 收到停止信号，等待 Spring Boot 关闭..."
  if [ -n "${APP_PID:-}" ]; then
    kill -TERM "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  exit 0
}
trap shutdown SIGTERM SIGINT

echo "[entrypoint] 启动 Spring Boot (profile=${SPRING_PROFILES_ACTIVE})..."
echo "[entrypoint] JAVA_OPTS=${JAVA_OPTS}"

# 启动 Spring Boot 后台
java $JAVA_OPTS \
  -DLINGYAO_JWT_SECRET="$LINGYAO_JWT_SECRET" \
  -jar "$APP_JAR" \
  --spring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
  --server.port=9091 \
  > /app/logs/lingyao.log 2>&1 &

APP_PID=$!
echo "[entrypoint] Spring Boot PID=$APP_PID"

# 等停机信号
wait "$APP_PID"