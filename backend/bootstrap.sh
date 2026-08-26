#!/usr/bin/env bash
# ============================================================
# 凌瑶智数 · 私有化部署一键启动脚本
#
# 用法：
#   ./bootstrap.sh           # 首次启动（生成密钥+启动）
#   ./bootstrap.sh start     # 启动服务
#   ./bootstrap.sh stop      # 停止服务
#   ./bootstrap.sh restart   # 重启服务
#   ./bootstrap.sh status    # 查看状态
#   ./bootstrap.sh logs      # 查看日志
#
# 部署目录结构：
#   .
#   ├── lingyao-platform.jar       # 主程序
#   ├── bootstrap.sh               # 本脚本
#   ├── .env.private               # 自动生成的密钥文件（首次启动后存在）
#   ├── data/                       # H2 数据目录（自动创建）
#   │   └── lingyao.mv.db
#   └── logs/
#       └── lingyao-private.log
#
# 客户机器要求：JDK 17+
# 端口：9091（可在 LINGYAO_PORT 环境变量覆盖）
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR_FILE="lingyao-platform.jar"
ENV_FILE=".env.private"
DATA_DIR="data"
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/lingyao-private.log"
PID_FILE="lingyao-private.pid"

PROFILE="private"
PORT="${LINGYAO_PORT:-9091}"

# ────────────────────── 工具函数 ──────────────────────

log_info()  { echo -e "\033[0;34m[INFO]\033[0m  $*"; }
log_ok()    { echo -e "\033[0;32m[OK]\033[0m    $*"; }
log_warn()  { echo -e "\033[0;33m[WARN]\033[0m  $*"; }
log_err()   { echo -e "\033[0;31m[ERR]\033[0m   $*"; }

require_jdk() {
    if ! command -v java >/dev/null 2>&1; then
        log_err "未检测到 java 命令，请先安装 JDK 17+"
        exit 1
    fi
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
    if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
        log_err "需要 JDK 17+，当前版本为 $JAVA_VER"
        exit 1
    fi
}

require_jar() {
    if [ ! -f "$JAR_FILE" ]; then
        log_err "未找到 $JAR_FILE，请将 jar 包放在脚本同目录下"
        exit 1
    fi
}

ensure_dirs() {
    mkdir -p "$DATA_DIR" "$LOG_DIR"
}

# ────────────────────── 密钥生成 ──────────────────────

generate_secret_if_needed() {
    if [ ! -f "$ENV_FILE" ]; then
        log_info "首次启动，生成 JWT 密钥..."
        JWT_SECRET=$(openssl rand -base64 48 | tr -d '/+=\n' | head -c 64)
        cat > "$ENV_FILE" <<EOF
# 凌瑶智数 · 私有化部署环境变量
# 警告：此文件包含 JWT 签名密钥，请勿泄露！
# 如需轮换密钥，重命名此文件后重新启动即可（会导致所有用户重新登录）

LINGYAO_JWT_SECRET=$JWT_SECRET
LINGYAO_PORT=$PORT
LINGYAO_DISPLAY_NAME=凌瑶智数（私有化）
EOF
        chmod 600 "$ENV_FILE"
        log_ok "JWT 密钥已生成并写入 $ENV_FILE"
    fi
}

load_env() {
    if [ -f "$ENV_FILE" ]; then
        set -a
        # shellcheck disable=SC1090
        source "$ENV_FILE"
        set +a
    fi
}

# ────────────────────── 服务控制 ──────────────────────

is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if ps -p "$pid" >/dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

start_service() {
    if is_running; then
        log_warn "服务已在运行（PID=$(cat "$PID_FILE")）"
        return 0
    fi

    require_jdk
    require_jar
    ensure_dirs
    generate_secret_if_needed
    load_env

    log_info "启动私有化模式 (profile=$PROFILE port=$PORT)..."
    nohup java -jar "$JAR_FILE" \
        --spring.profiles.active="$PROFILE" \
        --server.port="$PORT" \
        >> "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"
    sleep 5

    if is_running; then
        log_ok "服务已启动（PID=$pid）"
        log_ok "访问 http://localhost:$PORT/portal.html"
        log_ok "默认账号：admin / admin123（首次登录强制改密）"
        log_ok "日志：tail -f $LOG_FILE"
    else
        log_err "启动失败，查看日志："
        tail -20 "$LOG_FILE"
        exit 1
    fi
}

stop_service() {
    if ! is_running; then
        log_warn "服务未运行"
        rm -f "$PID_FILE"
        return 0
    fi
    local pid
    pid=$(cat "$PID_FILE")
    log_info "停止服务（PID=$pid）..."
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do
        if ! ps -p "$pid" >/dev/null 2>&1; then
            rm -f "$PID_FILE"
            log_ok "服务已停止"
            return 0
        fi
        sleep 1
    done
    log_warn "未停止，强制 kill -9"
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
}

show_status() {
    if is_running; then
        local pid
        pid=$(cat "$PID_FILE")
        log_ok "服务运行中（PID=$pid port=$PORT）"
        echo "   访问地址：http://localhost:$PORT/portal.html"
        echo "   H2 控制台：http://localhost:$PORT/h2-console"
        echo "   配置文件：$ENV_FILE"
        echo "   数据目录：$DATA_DIR/"
        echo "   日志文件：$LOG_FILE"
    else
        log_warn "服务未运行"
    fi
}

show_logs() {
    if [ -f "$LOG_FILE" ]; then
        tail -f "$LOG_FILE"
    else
        log_err "日志文件不存在：$LOG_FILE"
        exit 1
    fi
}

# ────────────────────── 主入口 ──────────────────────

case "${1:-start}" in
    start)   start_service ;;
    restart)  stop_service; start_service ;;
    stop)    stop_service ;;
    status)  show_status ;;
    logs)    show_logs ;;
    *)
        echo "用法：$0 {start|stop|restart|status|logs}"
        exit 1
        ;;
esac