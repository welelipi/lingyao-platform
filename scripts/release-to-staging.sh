#!/bin/bash
# 凌瑶智数 · staging 9092 一键发布脚本（V2.0.5 R-7 — 本地封装）
# 用法：./scripts/release-to-staging.sh [jar_path]
#   jar_path 默认 = backend/target/lingyao-platform.jar（项目根相对路径）
#
# 与 deploy-staging.sh 等价，但封装了：
#   1. 自动定位项目根 + jar 路径
#   2. 上传后自动通知飞书（如已配置 webhook）
#   3. 失败回滚到上一个 jar
#
# 前置条件：
#   - Mac 本地默认 ssh key 已 ssh-copy-id 到 CVM ubuntu 用户
#   - 本地 backend/target/lingyao-platform.jar 已构建（mvn clean package -DskipTests）
#   - CVM 上 systemd 服务 lingyao-backend-staging 已 enable

set -e

# ── 自动定位项目根 ──────────────────────
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

CVM="ubuntu@118.195.197.15"
JAR_NAME="lingyao-platform.jar"
LOCAL_JAR="${1:-$PROJECT_ROOT/backend/target/lingyao-platform.jar}"
SERVICE_NAME="lingyao-backend-staging"

# ── 前置校验 ───────────────────────────
if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 本地 jar 不存在: $LOCAL_JAR"
    echo "   请先 build: cd backend && mvn clean package -DskipTests"
    exit 1
fi

LOCAL_SIZE=$(du -h "$LOCAL_JAR" | awk '{print $1}')
echo "📦 待发布 jar: $LOCAL_JAR ($LOCAL_SIZE)"
echo "🎯 目标: staging 9092 (${SERVICE_NAME})"
echo "⏱️  预计耗时: 60-90 秒（Spring Boot 启动 30s + 健康检查 + 缓冲）"
echo ""

# ── Step 1: 调用统一部署脚本 ─────────────
# V2.0.6 R-7: 把 jarPath 透传给 deploy-staging.sh
bash "$SCRIPT_DIR/deploy-staging.sh" "$LOCAL_JAR"