#!/bin/bash
# 凌瑶智数 H2 数据库自动备份
# 由 cron 每天凌晨 3 点执行
# 备份位置：/mnt/datadisk0/backup/lingyao/lingyao-YYYYMMDD.mv.db
# 保留策略：最近 30 天

set -e

BACKUP_DIR=/mnt/datadisk0/backup/lingyao
SOURCE=/opt/lingyao/data/lingyao.mv.db
LOCK_FILE=/opt/lingyao/data/lingyao.mv.db.lock
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE=$BACKUP_DIR/lingyao-$TIMESTAMP.mv.db
LOG=/opt/lingyao/logs/backup.log

log() {
    echo "[$(date '+%F %T')] $1" >> "$LOG"
}

# 数据盘不存在则跳过（避免环境错误）
if [ ! -d /mnt/datadisk0 ]; then
    log "ERROR: /mnt/datadisk0 数据盘不存在，跳过备份"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# H2 CHECKPOINT：先把内存中的数据写入磁盘，保证备份一致性
if [ -f /usr/bin/java ]; then
    sudo -u root /usr/bin/java -cp /opt/lingyao/lingyao-platform.jar \
        org.h2.tools.Shell \
        -url "jdbc:h2:file:/opt/lingyao/data/lingyao" \
        -user sa \
        -password "" \
        -sql "CHECKPOINT" 2>/dev/null || true
fi

# 拷贝数据库文件
if [ -f "$SOURCE" ]; then
    cp -p "$SOURCE" "$BACKUP_FILE"
    SIZE=$(du -h "$BACKUP_FILE" | awk '{print $1}')
    log "OK: 备份成功 $BACKUP_FILE ($SIZE)"
    echo "✅ 备份成功: $BACKUP_FILE ($SIZE)"

    # 清理 30 天前的旧备份
    DELETED=$(find "$BACKUP_DIR" -name "lingyao-*.mv.db" -mtime +30 -delete -print | wc -l)
    if [ "$DELETED" -gt 0 ]; then
        log "CLEANUP: 清理 $DELETED 个 30 天前的备份"
    fi
else
    log "ERROR: 数据库文件不存在 $SOURCE"
    exit 1
fi
