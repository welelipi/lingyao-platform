#!/usr/bin/env bash
# 凌瑶主站 → NAS 手动备份脚本（V2.0.77.8 · 2026-09-08）
#
# 链路：主站本地资产 → /tmp/lingyao-backup-staging/ → rsync → NAS SMB 挂载点
# 备份范围（L2 中级）：PG 库 / JWT secret / 应用 jar / 静态资源 / 配置
# 触发：手动（主人拍板，非定时）
#
# 用法：
#   bash scripts/backup_to_nas.sh
#
# 前置条件：
#   1) NAS SMB 已挂载到 /Volumes/176XXXX8727（macOS Bonjour 自动 / Finder 手动均可）
#   2) NAS 上 CODE/lingyao-backup/ 目录已建好
#
# 备份目录结构（NAS 端）：
#   /Volumes/176XXXX8727/CODE/lingyao-backup/
#     2026-09-08_20-30-00/         ← 本次快照
#       pg_lingyao_dev.sql.gz      ← PG 数据库全量
#       assets.tar.gz              ← 密钥 + jar + 静态 + 配置
#       BACKUP_INFO.txt            ← 时间戳 + 大小 + 校验和
#     latest -> 2026-09-08_20-30-00  ← 软链指向最近一次（方便恢复时定位）
#     2026-09-07_20-30-00/         ← 历史快照（保留 30 天）

set -euo pipefail

# ── 配置 ────────────────────────────────────────────────────────────────
PROJECT_DIR="/Users/hua/Documents/myself/凌瑶"
NAS_MOUNT="/Volumes/176XXXX8727"
NAS_BACKUP_BASE="${NAS_MOUNT}/CODE/lingyao-backup"
LOCAL_STAGING="/tmp/lingyao-backup-staging"

# 备份保留策略（按时间目录数计）
RETENTION_COUNT=30

# 备份哪些东西（V2.0.77.8 L2 中级范围）
BACKUP_PG_DBS=("lingyao_dev")  # dev-pg profile 用的库
# V2.0.77.8a hotfix (2026-09-08): 修正 JWT secret 路径。原写
# ${PROJECT_DIR}/geo-backend-py/data/.jwt-secret 在主站根下不存在（主站真源是
# ${PROJECT_DIR}/data/.jwt_secret），导致 V2.0.77.8 全部 4 次备份都漏掉了 JWT
# secret——恢复后所有现有 token 都会失效。本次修复主站真源必含；GEOM 副本作为
# 子平台参考一并打包（不删，read-only）。
BACKUP_JWT_SECRET_MAIN="${PROJECT_DIR}/data/.jwt_secret"
BACKUP_JWT_SECRET_GEOM="${PROJECT_DIR}/geom/geo-backend-py/data/.jwt-secret"
BACKUP_JAR="${PROJECT_DIR}/backend/target/lingyao-platform.jar"
BACKUP_STATIC_SRC="${PROJECT_DIR}/website"
BACKUP_STATIC_BUILTIN="${PROJECT_DIR}/backend/src/main/resources/static"
BACKUP_CONFIG_YML="${PROJECT_DIR}/backend/src/main/resources/application*.yml"

# ── 日志 ────────────────────────────────────────────────────────────────
log() { echo "[$(date '+%F %T')] $*"; }

# ── 前置检查 ────────────────────────────────────────────────────────────
if [ ! -d "${NAS_MOUNT}" ]; then
    log "ERROR: NAS 未挂载到 ${NAS_MOUNT}"
    log "       请先用 Finder 连接 smb://192.168.3.60 或等待 macOS Bonjour 自动挂载"
    exit 1
fi
if [ ! -d "${NAS_BACKUP_BASE}" ]; then
    log "ERROR: NAS 备份目录不存在 ${NAS_BACKUP_BASE}"
    log "       请先在 NAS Web UI 或 Finder 的 CODE/ 下建子目录 lingyao-backup/"
    exit 1
fi

TIMESTAMP="$(date '+%Y-%m-%d_%H-%M-%S')"
SNAPSHOT_DIR="${NAS_BACKUP_BASE}/${TIMESTAMP}"
LOCAL_TMP="${LOCAL_STAGING}/${TIMESTAMP}"

mkdir -p "${SNAPSHOT_DIR}"
mkdir -p "${LOCAL_TMP}"

log "==========================================================="
log "凌瑶主站 → NAS 备份开始"
log "快照目录：${SNAPSHOT_DIR}"
log "==========================================================="

# ── 1. PG 数据库备份（pg_dump，压缩）──────────────────────────────
log "[1/4] PG 数据库备份..."
PG_BACKUP_FILE="${LOCAL_TMP}/pg_lingyao_dev.sql.gz"

# dev-pg profile: host=127.0.0.1 port=5432 db=lingyao_dev user=postgres
# 用主站嵌入的 PG 18.6 自带 pg_dump（系统 PATH 中无）
PG_DUMP_BIN="/Users/hua/Documents/myself/凌瑶/data/postgres-18/postgresql-18.6.0-aarch64-apple-darwin/bin/pg_dump"
if [ ! -x "${PG_DUMP_BIN}" ]; then
    log "  ERROR: pg_dump 未找到：${PG_DUMP_BIN}"
    log "  跳过 PG 备份继续（其他资产仍可备份）"
else
    if PGPASSWORD="lingyao" "${PG_DUMP_BIN}" \
            -h 127.0.0.1 -p 5432 -U postgres -d lingyao_dev \
            --clean --if-exists --quote-all-identifiers 2>/dev/null \
            | gzip > "${PG_BACKUP_FILE}"; then
        log "  PG 备份成功：$(du -h "${PG_BACKUP_FILE}" | awk '{print $1}')"
    else
        log "  PG 备份失败（dev-pg daemon 可能未起）"
        log "  跳过 PG 备份继续（其他资产仍可备份）"
        rm -f "${PG_BACKUP_FILE}"
    fi
fi

# ── 2. JWT secret + 应用 jar + 静态资源 + 配置 ────────────────────
log "[2/4] 应用资产打包..."
ASSETS_TAR="${LOCAL_TMP}/assets.tar.gz"

tar -czf "${ASSETS_TAR}" \
    -C "${PROJECT_DIR}" \
    $([ -f "${BACKUP_JWT_SECRET_MAIN}" ] && echo "data/.jwt_secret" || echo "") \
    $([ -f "${BACKUP_JWT_SECRET_GEOM}" ] && echo "geom/geo-backend-py/data/.jwt-secret" || echo "") \
    $([ -f "${BACKUP_JAR}" ] && echo "backend/target/lingyao-platform.jar" || echo "") \
    $([ -d "${BACKUP_STATIC_SRC}" ] && echo "website" || echo "") \
    $([ -d "${BACKUP_STATIC_BUILTIN}" ] && echo "backend/src/main/resources/static" || echo "") \
    "backend/src/main/resources/application.yml" \
    "backend/src/main/resources/application-dev-pg.yml" \
    "backend/src/main/resources/application-private.yml" \
    "CHANGELOG.md" \
    2>/dev/null || true

if [ -s "${ASSETS_TAR}" ]; then
    log "  应用资产打包成功：$(du -h "${ASSETS_TAR}" | awk '{print $1}')"
else
    log "  ERROR: 应用资产打包失败"
    exit 1
fi

# ── 3. 备份信息文件 ──────────────────────────────────────────────
log "[3/4] 生成备份信息..."
INFO_FILE="${LOCAL_TMP}/BACKUP_INFO.txt"
{
    echo "凌瑶主站备份信息"
    echo "============================================"
    echo "快照时间：$(date '+%F %T')"
    echo "主站路径：${PROJECT_DIR}"
    echo "备份目录：${SNAPSHOT_DIR}"
    echo ""
    echo "文件清单："
    echo "--------------------------------------------"
    cd "${LOCAL_TMP}" && ls -lh
    echo ""
    # V2.0.77.8a: 关键资产完整性自检，避免静默漏备
    echo "关键资产完整性自检（V2.0.77.8a）："
    echo "--------------------------------------------"
    if [ -s "${LOCAL_TMP}/assets.tar.gz" ]; then
        if tar -tzf "${LOCAL_TMP}/assets.tar.gz" 2>/dev/null | grep -q "^data/\.jwt_secret$"; then
            echo "  ✅ JWT secret (主站)   — data/.jwt_secret"
        else
            echo "  ❌ JWT secret (主站)   — 缺失！data/.jwt_secret 未被打包"
        fi
        if tar -tzf "${LOCAL_TMP}/assets.tar.gz" 2>/dev/null | grep -q "^geom/geo-backend-py/data/\.jwt-secret$"; then
            echo "  ✅ JWT secret (GEOM)   — geom/geo-backend-py/data/.jwt-secret"
        else
            echo "  ⚠️  JWT secret (GEOM)  — 缺失（GEOM 子平台副本未找到，不影响主站恢复）"
        fi
        if tar -tzf "${LOCAL_TMP}/assets.tar.gz" 2>/dev/null | grep -q "lingyao-platform\.jar$"; then
            echo "  ✅ 应用 jar            — backend/target/lingyao-platform.jar"
        else
            echo "  ❌ 应用 jar            — 缺失！"
        fi
        if tar -tzf "${LOCAL_TMP}/assets.tar.gz" 2>/dev/null | grep -q "^CHANGELOG\.md$"; then
            echo "  ✅ CHANGELOG           — CHANGELOG.md"
        else
            echo "  ❌ CHANGELOG           — 缺失！"
        fi
    fi
    if [ -s "${LOCAL_TMP}/pg_lingyao_dev.sql.gz" ]; then
        echo "  ✅ PG dump             — pg_lingyao_dev.sql.gz"
    else
        echo "  ❌ PG dump             — 缺失！dev-pg daemon 可能未起"
    fi
    echo ""
    echo "校验和（SHA-256，仅资产文件，不含本 BACKUP_INFO.txt）："
    echo "--------------------------------------------"
    # 显式列出资产文件名，避免把刚截断的 BACKUP_INFO.txt 自己算进去
    cd "${LOCAL_TMP}" && for f in $(ls | grep -v '^BACKUP_INFO.txt$'); do
        shasum -a 256 "$f" 2>/dev/null
    done
} > "${INFO_FILE}"

log "  备份信息写入：${INFO_FILE}"

# ── 4. rsync 推送到 NAS ──────────────────────────────────────────
log "[4/4] 推送到 NAS..."
rsync -av --progress "${LOCAL_TMP}/" "${SNAPSHOT_DIR}/" 2>&1 | tail -15

# 更新 latest 软链
ln -sfn "${TIMESTAMP}" "${NAS_BACKUP_BASE}/latest" 2>/dev/null || true

# 清理本地 staging
rm -rf "${LOCAL_TMP}"

log ""
log "==========================================================="
log "✅ 备份完成：${SNAPSHOT_DIR}"
log "==========================================================="
echo ""
echo "NAS 端快照："
ls -la "${NAS_BACKUP_BASE}/" 2>&1 | tail -10

# ── 5. 清理超过 RETENTION_COUNT 的旧快照 ─────────────────────────
log ""
log "[cleanup] 清理超过 ${RETENTION_COUNT} 天的旧快照..."
# 注：SMB 挂载点 rm 可能受限，清理失败不致命
OLD_DIRS=$(ls -1t "${NAS_BACKUP_BASE}/" 2>/dev/null | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}_' | tail -n +$((RETENTION_COUNT + 1)))
if [ -n "${OLD_DIRS}" ]; then
    echo "${OLD_DIRS}" | while read -r old; do
        rm -rf "${NAS_BACKUP_BASE}/${old}" 2>/dev/null && log "  清理：${old}" || log "  跳过：${old}（SMB 删除受限，请到 Finder 手动清理）"
    done
else
    log "  无需清理（备份数 ≤ ${RETENTION_COUNT}）"
fi

log ""
log "DONE. 主人可在 Finder 打开 smb://192.168.3.60 → 176XXXX8727 → CODE → lingyao-backup 查看"
