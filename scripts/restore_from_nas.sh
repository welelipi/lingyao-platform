#!/usr/bin/env bash
# 凌瑶主站 ← NAS 恢复脚本（V2.0.77.8 · 2026-09-08）
#
# 链路：NAS SMB 挂载点 → /tmp/lingyao-restore-staging/ → 校验 → 恢复到主站
# 默认恢复 latest 快照，支持指定快照名
#
# 用法：
#   bash scripts/restore_from_nas.sh                   # 恢复 latest
#   bash scripts/restore_from_nas.sh 2026-09-08_20-24-51   # 恢复指定快照
#   bash scripts/restore_from_nas.sh --dry-run latest   # 仅校验不写入
#
# 前置条件：
#   1) NAS SMB 已挂载到 /Volumes/176XXXX8727
#   2) PG dev-pg daemon 已停（恢复 PG 期间不能有连接）
#
# ⚠️ 警告：恢复会覆盖当前主站资产！脚本会先备份当前到 /tmp/lingyao-pre-restore-snapshot/

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────────────────────
PROJECT_DIR="/Users/hua/Documents/myself/凌瑶"
NAS_MOUNT="/Volumes/176XXXX8727"
NAS_BACKUP_BASE="${NAS_MOUNT}/CODE/lingyao-backup"
LOCAL_STAGING="/tmp/lingyao-restore-staging"
PRE_RESTORE_SNAPSHOT="/tmp/lingyao-pre-restore-snapshot-$(date '+%Y%m%d_%H%M%S')"

PG_DUMP_BIN="/Users/hua/Documents/myself/凌瑶/data/postgres-18/postgresql-18.6.0-aarch64-apple-darwin/bin/pg_dump"
PSQL_BIN="/Users/hua/Documents/myself/凌瑶/data/postgres-18/postgresql-18.6.0-aarch64-apple-darwin/bin/psql"

# ── 参数解析 ────────────────────────────────────────────────────────────
DRY_RUN=false
SNAPSHOT_NAME=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            echo "用法: bash $0 [snapshot_name|--dry-run]"
            echo "  snapshot_name: 指定快照目录名（如 2026-09-08_20-24-51），默认 latest"
            echo "  --dry-run: 仅校验备份完整性，不实际恢复"
            exit 0
            ;;
        *)
            SNAPSHOT_NAME="$1"
            shift
            ;;
    esac
done

# ── 日志 ────────────────────────────────────────────────────────────────
log() { echo "[$(date '+%F %T')] $*"; }

# ── 前置检查 ────────────────────────────────────────────────────────────
if [ ! -d "${NAS_MOUNT}" ]; then
    log "ERROR: NAS 未挂载到 ${NAS_MOUNT}"
    exit 1
fi
if [ ! -d "${NAS_BACKUP_BASE}" ]; then
    log "ERROR: NAS 备份目录不存在 ${NAS_BACKUP_BASE}"
    exit 1
fi

# 解析快照目录
if [ -z "${SNAPSHOT_NAME}" ]; then
    SNAPSHOT_NAME="latest"
fi

if [ "${SNAPSHOT_NAME}" = "latest" ]; then
    SNAPSHOT_DIR=$(readlink "${NAS_BACKUP_BASE}/latest" 2>/dev/null) || {
        log "ERROR: latest 软链不存在，请指定具体快照名"
        exit 1
    }
    SNAPSHOT_DIR="${NAS_BACKUP_BASE}/${SNAPSHOT_DIR}"
else
    SNAPSHOT_DIR="${NAS_BACKUP_BASE}/${SNAPSHOT_NAME}"
fi

if [ ! -d "${SNAPSHOT_DIR}" ]; then
    log "ERROR: 快照目录不存在 ${SNAPSHOT_DIR}"
    exit 1
fi

log "==========================================================="
log "凌瑶主站 ← NAS 恢复开始"
log "快照：${SNAPSHOT_DIR}"
log "模式：$([ "${DRY_RUN}" = "true" ] && echo "DRY-RUN（仅校验）" || echo "实际恢复")"
log "==========================================================="

# ── 1. rsync 拉到本地 staging ─────────────────────────────────────
log "[1/4] 拉取备份到本地 staging..."
mkdir -p "${LOCAL_STAGING}"
rsync -av "${SNAPSHOT_DIR}/" "${LOCAL_STAGING}/" 2>&1 | tail -5

# ── 2. 校验 SHA-256 ──────────────────────────────────────────────
log "[2/4] 校验备份完整性..."
if [ -f "${LOCAL_STAGING}/BACKUP_INFO.txt" ]; then
    cd "${LOCAL_STAGING}"
    EXPECTED_HASHES=$(grep -E '^[a-f0-9]{64}' BACKUP_INFO.txt || true)
    CHECK_FAILED=false
    while IFS= read -r line; do
        HASH=$(echo "$line" | awk '{print $1}')
        FILE=$(echo "$line" | awk '{print $2}')
        if [ -f "${FILE}" ]; then
            ACTUAL_HASH=$(shasum -a 256 "${FILE}" 2>/dev/null | awk '{print $1}')
            if [ "${HASH}" = "${ACTUAL_HASH}" ]; then
                log "  ✅ ${FILE} 校验通过"
            else
                log "  ❌ ${FILE} 校验失败"
                log "     期望: ${HASH}"
                log "     实际: ${ACTUAL_HASH}"
                CHECK_FAILED=true
            fi
        else
            log "  ⚠️ ${FILE} 不存在（跳过）"
        fi
    done <<< "${EXPECTED_HASHES}"

    if [ "${CHECK_FAILED}" = "true" ]; then
        log "ERROR: 备份文件校验失败，停止恢复！"
        exit 1
    fi
else
    log "  ⚠️ 未找到 BACKUP_INFO.txt，跳过 SHA-256 校验"
fi

# ── DRY-RUN 提前结束 ────────────────────────────────────────────
if [ "${DRY_RUN}" = "true" ]; then
    log ""
    log "DRY-RUN 完成，备份完整性 OK（未实际恢复）"
    rm -rf "${LOCAL_STAGING}"
    exit 0
fi

# ── 3. 备份当前主站（防恢复失败兜底）───────────────────────────
log "[3/4] 备份当前主站到 ${PRE_RESTORE_SNAPSHOT}..."
mkdir -p "${PRE_RESTORE_SNAPSHOT}"
[ -d "${PROJECT_DIR}/geo-backend-py/data/.jwt-secret" ] && cp -p "${PROJECT_DIR}/geo-backend-py/data/.jwt-secret" "${PRE_RESTORE_SNAPSHOT}/" 2>/dev/null || true
[ -f "${PROJECT_DIR}/backend/target/lingyao-platform.jar" ] && cp "${PROJECT_DIR}/backend/target/lingyao-platform.jar" "${PRE_RESTORE_SNAPSHOT}/" 2>/dev/null || true
[ -f "${LOCAL_STAGING}/pg_lingyao_dev.sql.gz" ] && cp "${LOCAL_STAGING}/pg_lingyao_dev.sql.gz" "${PRE_RESTORE_SNAPSHOT}/pre-restore-pg.sql.gz" 2>/dev/null || true
log "  当前主站备份完成"

# ── 4. 实际恢复 ──────────────────────────────────────────────────
log "[4/4] 恢复资产..."

# 4.1 恢复 assets.tar.gz
if [ -f "${LOCAL_STAGING}/assets.tar.gz" ]; then
    log "  [4.1] 解压 assets.tar.gz 到 ${PROJECT_DIR}/..."
    cd "${PROJECT_DIR}"
    tar -xzf "${LOCAL_STAGING}/assets.tar.gz" 2>&1 | tail -3
    log "  ✅ assets 恢复完成"
else
    log "  ⚠️ assets.tar.gz 不存在，跳过"
fi

# 4.2 恢复 PG 数据库
if [ -f "${LOCAL_STAGING}/pg_lingyao_dev.sql.gz" ]; then
    log "  [4.2] 恢复 PG 数据库 lingyao_dev..."
    if [ ! -x "${PSQL_BIN}" ]; then
        log "  ERROR: psql 未找到：${PSQL_BIN}"
        log "  跳过 PG 恢复"
    else
        # 先停 PG 连接（如果有 daemon 在跑，需要 kill）
        log "  提示：如 dev-pg daemon 在跑，请先 kill 后再恢复 PG"
        log "  执行：lsof -iTCP:9091 -P | grep LISTEN | awk '{print \$2}' | xargs kill -9"

        # 解压并恢复
        gunzip -c "${LOCAL_STAGING}/pg_lingyao_dev.sql.gz" | \
            PGPASSWORD="lingyao" "${PSQL_BIN}" \
                -h 127.0.0.1 -p 5432 -U postgres -d lingyao_dev 2>&1 | tail -5
        log "  ✅ PG 恢复完成"
    fi
else
    log "  ⚠️ pg_lingyao_dev.sql.gz 不存在，跳过"
fi

# ── 清理 ─────────────────────────────────────────────────────────
rm -rf "${LOCAL_STAGING}"

log ""
log "==========================================================="
log "✅ 恢复完成"
log "==========================================================="
log ""
log "📋 后续必须做的事："
log "  1) 重启主站 daemon："
log "     cd /Users/hua/Documents/myself/凌瑶 && LINGYAO_PROFILE=dev-pg python3 scripts/start_backend_daemon.py"
log ""
log "  2) 重新挂载 NAS SMB（如已断开）："
log "     macOS Bonjour 自动 / Finder 手动连接 smb://192.168.3.60"
log ""
log "  3) 验证恢复："
log "     curl http://localhost:9091/api/_diag/version"
log ""
log "📁 恢复前的快照备份位置：${PRE_RESTORE_SNAPSHOT}"
log "   （如恢复失败可手动 rollback）"
