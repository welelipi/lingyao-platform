#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════
# 凌瑶智数 · H2 数据库热备份脚本
# ────────────────────────────────────────────────────────────
# 用法（在容器内执行）：
#   docker exec lingyao-platform /app/backup.sh
#
# 或手动指定备份目录：
#   docker exec lingyao-platform /app/backup.sh /path/to/backup
#
# 备份产物：
#   /app/backup/lingyao_YYYYMMDD_HHMMSS.zip
#   （包含 lingyao.mv.db / lingyao.trace.db（如有） + 元数据）
#
# 恢复方式：
#   1. 停容器
#   2. 解压备份到 /app/data/
#   3. 删除 .jwt_secret（强制所有用户重新登录，避免密钥不一致）
#   4. 重启容器
# ════════════════════════════════════════════════════════════

set -e

DATA_DIR="${1:-/app/data}"
BACKUP_DIR="${2:-/app/backup}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="$BACKUP_DIR/lingyao_${TIMESTAMP}.zip"

mkdir -p "$BACKUP_DIR"

echo "[backup] 数据目录：$DATA_DIR"
echo "[backup] 备份目录：$BACKUP_DIR"

# 收集 H2 文件
TMP_DIR=$(mktemp -d)
cp "$DATA_DIR"/lingyao*.db "$TMP_DIR/" 2>/dev/null || true
[ -f "$DATA_DIR/.jwt_secret" ] && cp "$DATA_DIR/.jwt_secret" "$TMP_DIR/"

# 元数据
cat > "$TMP_DIR/META.txt" <<EOF
backup_time=$(date -Iseconds)
hostname=$(hostname)
lingyao_version=1.0.0
contents=$(ls "$TMP_DIR" | tr '\n' ' ')
EOF

# 打包
cd "$TMP_DIR"
zip -q "$BACKUP_FILE" .
cd - > /dev/null
rm -rf "$TMP_DIR"

# 清理 30 天前的备份
find "$BACKUP_DIR" -name "lingyao_*.zip" -mtime +30 -delete 2>/dev/null || true

echo "[backup] 备份成功：$BACKUP_FILE"
ls -lh "$BACKUP_FILE"