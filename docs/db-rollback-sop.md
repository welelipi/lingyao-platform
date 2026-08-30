# 数据库迁移回滚 SOP（v1.0 · 2026-08-30）

> **目的**：万一数据库迁移出问题（数据丢失、性能崩盘、方言不兼容），如何 5 分钟内回滚到迁移前状态
>
> **适用**：POR-M H2→SQLite（P0-1）/ MPDM H2→SQLite（未做）/ 未来 SQLite→PG / PG 版本升级

---

## 一、回滚总原则

### 1.1 三件铁律

1. **先备份，再动手**（铁律 #11）— **永远在迁移前先备份** ✅
2. **保留上一个稳定版本的备份** — 至少 7 天
3. **回滚比迁移快** — 备份本身就是回滚最快路径

### 1.2 回滚决策树

```
出问题了 → 
  ├─ 数据没丢，只是性能/兼容问题？→ 回退版本（不改 DB 类型）
  ├─ 数据丢了？→ 从备份恢复
  ├─ 备份也坏？→ 立刻停止写入，联系 DBA
  └─ 完全不知道问题在哪？→ 先停止服务，再排查
```

---

## 二、POR-M H2 → SQLite 回滚（v0.8.154 → v0.8.153）

### 2.1 触发场景

- Spring Boot 启动报方言错误
- 业务接口 500
- 35 张表部分表创建失败
- SQLite 数据库锁死

### 2.2 回滚步骤（5 分钟内）

```bash
# 0. 停止后端
kill $(cat /tmp/mpd-myself-backend.pid)

# 1. 恢复 H2 数据库（备份在 _backups-2026-08-30/）
cp /Users/hua/Documents/myself/POR-M/_backups-2026-08-30/h2data-root/mingshu.mv.db \
   /Users/hua/Documents/myself/POR-M/明枢\ 智能协作平台/10_Backend/mingshu-backend/h2data/mingshu.mv.db

cp /Users/hua/Documents/myself/POR-M/_backups-2026-08-30/h2data-backend/mingshu.mv.db \
   /Users/hua/Documents/myself/POR-M/明枢\ 智能协作平台/10_Backend/mingshu-backend/h2data/mingshu.mv.db

# 2. 回滚代码（git revert commit 35d8555）
cd /Users/hua/Documents/myself/POR-M
git revert --no-commit 35d8555

# 或者用 git reset（更激进，会丢失 P0-1 之后的 commit）
# git reset --hard 9ad5265

# 3. 删除新创建的 sqlite-data/（避免混淆）
rm -rf '明枢 智能协作平台/10_Backend/mingshu-backend/sqlite-data/'

# 4. 重启后端（恢复 H2 profile）
cd '明枢 智能协作平台/10_Backend/mingshu-backend'
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 2.3 验证回滚成功

```bash
# 1. 后端启动日志应该看到 H2
curl http://127.0.0.1:8280/api/_diag/version  # 返回 0.8.153

# 2. H2 Console（dev profile 启用）
# 浏览器打开 http://localhost:8280/h2-console
# JDBC URL: jdbc:h2:file:./h2data/mingshu
# 用户: sa / 密码: 空

# 3. 业务接口冒烟
curl http://127.0.0.1:8280/api/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
```

---

## 三、SQLite 单文件损坏回滚

### 3.1 触发场景

- SQLite 文件损坏（`database disk image is malformed`）
- 索引损坏
- WAL 文件不一致

### 3.2 完整恢复步骤

```bash
# 1. 立即停止所有写入
kill $(cat /tmp/mpd-myself-backend.pid)  # MPDM
kill $(cat /tmp/mingshu-backend.pid)      # POR-M

# 2. 备份当前损坏文件（不要删）
mkdir -p _corrupted-$(date +%Y%m%d)
mv backend/data/hospital_hoyuan.db* _corrupted-$(date +%Y%m%d)/

# 3. 从 _backups 恢复
cp _backups-2026-08-30/mpdm-sqlite-backup-*.tar.gz /tmp/
cd /tmp && tar -xzf mpdm-sqlite-backup-*.tar.gz
cp hospital_hoyuan.db* /Users/hua/Documents/myself/MPD-myself/backend/data/

# 4. 验证文件完整性
sqlite3 /Users/hua/Documents/myself/MPD-myself/backend/data/hospital_hoyuan.db "PRAGMA integrity_check;"
# 应输出: ok

# 5. 重启后端
nohup python3 scripts/start_backend_daemon.py > /dev/null 2>&1 &
```

### 3.3 SQLite 应急修复（不恢复但能抢救）

如果只有轻微损坏，尝试 `REINDEX` 或 `.dump`：

```bash
# 抢救模式 1：导出再导入（保留所有数据）
sqlite3 old.db ".dump" | sqlite3 new.db

# 抢救模式 2：VACUUM + REINDEX
sqlite3 old.db "VACUUM; REINDEX;"
```

---

## 四、PG 数据误删/误改回滚

### 4.1 触发场景

- `DROP TABLE` 误删
- `UPDATE`/`DELETE` 漏 where 条件
- PG 容器被误删

### 4.2 立即停止写入

```bash
# 1. 停止后端
docker stop lingyao-backend
# 2. 停止 PG 写入
docker exec lingyao-postgres pg_terminate_backend -U lingyao_app -d lingyao
# 3. 不要再做任何 DDL/DML
```

### 4.3 从 pg_dump 恢复（5 分钟）

```bash
# 1. 找到最近一次备份
ls -lt /mnt/datadisk0/backup/lingyao/*.dump | head -5

# 2. 恢复（用 docker exec 灌进容器）
LATEST=$(ls -t /mnt/datadisk0/backup/lingyao/*.dump | head -1)
gunzip -c "$LATEST" | \
  docker exec -i lingyao-postgres psql -U lingyao_app -d lingyao

# 3. 验证数据
docker exec lingyao-postgres psql -U lingyao_app -d lingyao \
  -c "SELECT COUNT(*) FROM users;"

# 4. 重启后端
docker start lingyao-backend
```

### 4.4 PITR（Point-in-Time Recovery）回滚

如果 pg_dump 太老（丢了最近 1 小时数据），用 PITR：

```bash
# 1. 停止 PG
docker stop lingyao-postgres

# 2. 备份当前数据目录（避免 PITR 失败时无法再次尝试）
cp -r /var/lib/postgresql/data /tmp/pg-before-pitr

# 3. 创建恢复标记（告诉 PG 「恢复到这个时间点」）
echo "recovery_target_time = '2026-08-30 14:25:00+08'" >> /var/lib/postgresql/data/recovery.conf
echo "restore_command = 'cp /mnt/datadisk0/wal/%f %p'" >> /var/lib/postgresql/data/recovery.conf
touch /var/lib/postgresql/data/recovery.signal

# 4. 启动 PG（会自动进入 recovery 模式）
docker start lingyao-postgres

# 5. 验证
docker exec lingyao-postgres psql -U lingyao_app -d lingyao \
  -c "SELECT NOW();"
```

⚠️ PITR 是高级操作，需要：
- PG 已开启 `wal_level=replica`
- WAL 归档目录配置正确
- 有经验的人在场

---

## 五、备份保留策略

### 5.1 本地开发环境

| 备份类型 | 保留时长 | 位置 |
|---|---|---|
| 每次迁移前 | 永久（不删）| `_backups-<日期>/` |
| 自动 daily | 7 天 | `/tmp/sqlite-daily/` |
| 自动 weekly | 30 天 | `/tmp/sqlite-weekly/` |

### 5.2 生产环境（CVM）

| 备份类型 | 保留时长 | 位置 | 触发 |
|---|---|---|---|
| pg_dump daily | 30 天 | `/mnt/datadisk0/backup/lingyao/` | cron 0 3 * * * |
| pg_dump weekly | 365 天 | `/mnt/datadisk0/backup/lingyao-weekly/` | cron 0 4 * * 0 |
| WAL 归档 | 7 天 | `/mnt/datadisk0/wal/` | archive_mode=on |

### 5.3 备份验证（每月 1 次）

```bash
# 1. 选一个 backup 文件
BACKUP=$(ls -t /mnt/datadisk0/backup/lingyao/*.dump | head -1)

# 2. 恢复到测试容器
docker run --rm -d --name pg-test-restore -e POSTGRES_PASSWORD=test \
  -v $(pwd)/restore-test:/var/lib/postgresql/data postgres:16-alpine
sleep 10

# 3. 灌数据
gunzip -c "$BACKUP" | \
  docker exec -i pg-test-restore psql -U postgres -d postgres

# 4. 验证关键表
docker exec pg-test-restore psql -U postgres -d postgres \
  -c "SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM orders;"

# 5. 清理
docker stop pg-test-restore
```

---

## 六、回滚后必须做的事（事后总结）

### 6.1 24 小时内

- [ ] 写事故复盘报告
- [ ] 通知主人
- [ ] 把回滚步骤文档化
- [ ] 检查是否有数据丢失（如有，从更长保留期备份恢复）

### 6.2 1 周内

- [ ] 修复根因（不是绕过）
- [ ] 加强监控/告警
- [ ] 更新回滚 SOP
- [ ] 团队内部分享

---

## 七、回滚检查清单（5 分钟模板）

| 检查项 | 状态 |
|---|---|
| 备份存在性 | ✅ |
| 备份时效（< 24h 内？） | ✅ |
| 备份完整性（integrity_check / pg_dump dry-run） | ✅ |
| 当前 DB 已停止写入 | ✅ |
| 回滚步骤明确（命令已 dry-run） | ✅ |
| 验证回滚成功（业务接口冒烟） | ✅ |
| 通知主人 + 团队 | ✅ |
| 事后复盘 | ✅ |

---

## 八、变更记录

| 版本 | 日期 | 改动 |
|---|---|---|
| v1.0 | 2026-08-30 | 初版，POR-M H2→SQLite / SQLite 单文件 / PG 三类回滚 SOP |

---

## 九、参考文档

- `凌瑶/docs/db-selection-spec.md` · 凌瑶空间 DB 选型规范
- `凌瑶/docs/db-component-inventory-2026-08-30.md` · 4 组件现状盘点
- `凌瑶/deploy/cvm/backup-pg.sh` · PG 自动备份脚本
- `明枢/DEPLOYMENT.md` §8 · POR-M 备份恢复
