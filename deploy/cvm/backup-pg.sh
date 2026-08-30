#!/bin/bash
# 凌瑶智数 PostgreSQL 数据库自动备份（替换旧的 backup-h2.sh）
# 由 cron 每天凌晨 3 点执行（云端）或手动（本地）
# 备份位置：${BACKUP_DIR:-/mnt/datadisk0/backup/lingyao}/lingyao_pg_backup_YYYYMMDD-HHMMSS.sql.gz
# 保留策略：最近 30 天
#
# 使用：
#   1) 默认配置（云端 CVM 生产）：./backup-pg.sh
#   2) 指定参数：BACKUP_DIR=/path POSTGRES_PASSWORD=xxx ./backup-pg.sh
#   3) 本地 Docker 容器：CONTAINER=lingyao-postgres ./backup-pg.sh
#
# 配套 cron（CVM 生产）：
#   0 3 * * * /opt/lingyao/deploy/cvm/backup-pg.sh >> /opt/lingyao/logs/backup.log 2>&1

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# 配置（环境变量可覆盖）
# ─────────────────────────────────────────────────────────────────────────────
BACKUP_DIR="${BACKUP_DIR:-/mnt/datadisk0/backup/lingyao}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_FILE="${BACKUP_DIR}/lingyao_pg_backup_${TIMESTAMP}.sql.gz"
LOG_FILE="${LOG_FILE:-/opt/lingyao/logs/backup.log}"

POSTGRES_DB="${POSTGRES_DB:-lingyao}"
POSTGRES_USER="${POSTGRES_USER:-lingyao_app}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-}"
POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

# 本地 Docker 模式（容器名）
CONTAINER="${CONTAINER:-}"

# 保留天数
RETENTION_DAYS="${RETENTION_DAYS:-30}"

log() {
    # 确保日志文件父目录存在（云端默认 /opt/lingyao/logs 本地可能没有）
    mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
    echo "[$(date '+%F %T')] $1" | tee -a "$LOG_FILE" 2>/dev/null || \
        echo "[$(date '+%F %T')] $1"
}

# ─────────────────────────────────────────────────────────────────────────────
# 前置检查
# ─────────────────────────────────────────────────────────────────────────────
if [ ! -d "${BACKUP_DIR%/*}" ]; then
    log "ERROR: 备份父目录不存在 (${BACKUP_DIR%/*})，跳过"
    log "       生产环境预期 /mnt/datadisk0 已挂载；本地请设 BACKUP_DIR=/tmp/lingyao-backup"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# ─────────────────────────────────────────────────────────────────────────────
# 执行备份
# ─────────────────────────────────────────────────────────────────────────────
if [ -n "$CONTAINER" ]; then
    # ─────────────────────────────────────────────────────────────────
    # 本地 Docker 模式：docker exec 进容器跑 pg_dump
    # ─────────────────────────────────────────────────────────────────
    log "INFO: 本地 Docker 模式 (container=$CONTAINER)"
    if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
        log "ERROR: 容器 $CONTAINER 未运行"
        exit 1
    fi

    docker exec \
        -e PGPASSWORD="$POSTGRES_PASSWORD" \
        "$CONTAINER" \
        pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --quote-all-identifiers \
        | gzip > "$BACKUP_FILE"
else
    # ─────────────────────────────────────────────────────────────────
    # 云端 / 本地原生模式：直接调 pg_dump
    # ─────────────────────────────────────────────────────────────────
    if [ -z "$POSTGRES_PASSWORD" ]; then
        log "ERROR: POSTGRES_PASSWORD 未设置"
        log "       用法: POSTGRES_PASSWORD=xxx $0 或 docker exec 模式: CONTAINER=lingyao-postgres $0"
        exit 1
    fi

    if ! command -v pg_dump >/dev/null 2>&1; then
        log "ERROR: pg_dump 未安装，请先 apt install postgresql-client 或 yum install postgresql"
        exit 1
    fi

    log "INFO: 原生模式 (host=$POSTGRES_HOST port=$POSTGRES_PORT db=$POSTGRES_DB)"
    PGPASSWORD="$POSTGRES_PASSWORD" pg_dump \
        -h "$POSTGRES_HOST" \
        -p "$POSTGRES_PORT" \
        -U "$POSTGRES_USER" \
        -d "$POSTGRES_DB" \
        --clean --if-exists --quote-all-identifiers \
        | gzip > "$BACKUP_FILE"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 校验 + 清理
# ─────────────────────────────────────────────────────────────────────────────
if [ -s "$BACKUP_FILE" ]; then
    SIZE=$(du -h "$BACKUP_FILE" | awk '{print $1}')
    log "OK: 备份成功 $BACKUP_FILE ($SIZE)"

    # 清理 $RETENTION_DAYS 天前的备份
    DELETED=$(find "$BACKUP_DIR" -name "lingyao_pg_backup_*.sql.gz" -mtime +${RETENTION_DAYS} -delete -print | wc -l)
    if [ "$DELETED" -gt 0 ]; then
        log "CLEANUP: 清理 $DELETED 个 ${RETENTION_DAYS} 天前的备份"
    fi
else
    log "ERROR: 备份文件为空 $BACKUP_FILE，删除"
    rm -f "$BACKUP_FILE"
    exit 1
fi