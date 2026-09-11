# 凌瑶空间 · 子平台 NAS 灾备接入规范 · v1.0

> **文档定位**：跨窗口协调 SOP（主站 2026-09-08 V2.0.77.8 主站先行落地，沉淀给 7 个子平台复用）
> **接收方**：凌瑶空间旗下所有子产品窗口
> - 🪞 GEOM 棱镜（`~/Documents/myself/凌瑶/geom/`）
> - 📋 DINFO 辰录（`~/Documents/myself/Dinfo/`）
> - 🏥 MPDM 皓元（`~/Documents/myself/MPD-myself/`）
> - 🧪 AIDD 源策（`~/Documents/myself/AIDD/`）
> - 🤝 POR-M 明枢（`~/Documents/myself/POR-M/`）
> - 📊 MED-PPT 医呈（`~/Documents/myself/MED-PPT/`）
> - 🏛️ 主站自身（`~/Documents/myself/凌瑶/`）
>
> **目标**：每个子平台按本文规范，30 分钟内接入 NAS 灾备链路

---

## 1. 背景

主站 2026-09-08 完成 V2.0.77.8 灾备链路建立（详见 `docs/凌瑶主站-NAS灾备手册.md`）。链路已验证可用。各子平台应复用 NAS 资源，但**各自维护自己的备份脚本与目录**（隔离原则）。

---

## 2. 共享 NAS 资源

### 2.1 物理位置

- **型号**：极空间 Z4 Pro
- **内网 IP**：`192.168.3.60`
- **SSH 端口**：`10000`（极空间自定义，非默认 22）
- **SMB 自动挂载点**：`/Volumes/176XXXX8727/`（macOS Bonjour 自动发现）
- **极空间后台**：`http://192.168.3.60:5050/` 或 `http://192.168.3.60:5055/`
- **共享名**：`176XXXX8727`（极空间对用户名前 4 位打码是协议层隐私保护）
- **CODE 子目录**：Web UI 显示的"CODE"文件夹（主人共享文件夹名）

### 2.2 NAS 端 SMB 权限

- **路径权限**：`/Volumes/176XXXX8727/CODE/` 是 `drwx------ 1 hua staff`（owner=hua，700 权限）
- **写入权限**：主站 macOS 用户 `hua` 可读可写
- **删除限制**：SMB 协议对隐藏文件 + 刚写入文件的 rm 有限制（"Operation not permitted"）。**写入不受影响，仅删除需走 Finder**

### 2.3 macOS 接入步骤（新设备）

```bash
# 1. Finder → 前往 → 连接服务器 (⌘K)
#    地址：smb://192.168.3.60
#    选共享：176XXXX8727
#    账号密码：主站 macOS 用户的 SMB 凭据（存 macOS Keychain）
#
# 2. 验证挂载
mount | grep lingyao
# 期望输出：...on /Volumes/176XXXX8727 (smbfs, ...)
#
# 3. 验证可写
touch /Volumes/176XXXX8727/CODE/.write_test && echo OK || echo FAIL
```

---

## 3. NAS 备份目录规划

### 3.1 命名约定（强制）

```
/Volumes/176XXXX8727/CODE/<product_code>/backup/
```

| 子平台 | product_code | 备份根目录 |
|---|---|---|
| 主站 | `lingyao` | `/Volumes/176XXXX8727/CODE/lingyao/backup/` |
| GEOM 棱镜 | `geom` | `/Volumes/176XXXX8727/CODE/geom/backup/` |
| DINFO 辰录 | `dinfo` | `/Volumes/176XXXX8727/CODE/dinfo/backup/` |
| MPDM 皓元 | `mpdm` | `/Volumes/176XXXX8727/CODE/mpdm/backup/` |
| AIDD 源策 | `aidd` | `/Volumes/176XXXX8727/CODE/aidd/backup/` |
| POR-M 明枢 | `porm` | `/Volumes/176XXXX8727/CODE/porm/backup/` |
| MED-PPT 医呈 | `medppt` | `/Volumes/176XXXX8727/CODE/medppt/backup/` |

**`product_code` 必须与子产品的 `Product.code` 字段一致**（主站 `sys_product.code`），便于跨窗口聚合统计。

### 3.2 子目录创建

每个子平台窗口负责建自己的 `CODE/<product_code>/backup/` 子目录。**主站不代建**（按铁律 0 边界）。

主站 macOS 用户有权限建（在 `CODE/` 下建子目录 owner 自动是 hua）：

```bash
# 例：AIDD 窗口建自己备份目录
mkdir -p /Volumes/176XXXX8727/CODE/aidd/backup
```

### 3.3 快照目录结构（强制）

```
CODE/<product_code>/backup/
├── 2026-09-08_20-30-00/              ← 单次快照
│   ├── <product_code>_pg.sql.gz      ← 数据库（如 PG）
│   ├── <product_code>_sqlite.db      ← 数据库（如 SQLite，直接备份文件即可）
│   ├── <product_code>_h2.mv.db       ← 数据库（如 H2）
│   ├── assets.tar.gz                 ← 密钥 + jar + 静态 + 配置
│   └── BACKUP_INFO.txt               ← 时间戳 + 大小 + SHA-256
├── 2026-09-07_20-30-00/              ← 历史快照
├── 2026-09-06_20-30-00/
└── latest -> 2026-09-08_20-30-00     ← 软链，指向最新
```

**所有备份文件名以 `<product_code>_` 前缀开头**，避免多子平台共用 NAS 时混淆。

---

## 4. 备份等级

子平台按需选择，不要一上来就 L3（成本高、复杂度高）。

| 等级 | 内容 | 触发 | 保留 | 适用 |
|---|---|---|---|---|
| **L1** | 仅数据库 | 手动 | 30 天 | 库丢了能重建、代码有 git |
| **L2** | 数据库 + 密钥 + jar + 静态 + 配置（推荐）| 手动 | 30 天 | 丢一台机器能 30 分钟重建 |
| **L3** | L2 + 异地云端（OSS / S3）+ 自动演练 | 手动 + 月度自动演练 | 90 天 | NAS 全毁可从云端拉回 |

**主站当前 L2**，子平台起步建议 **L2**（与主站对齐），后期需要 L3 时再升级。

---

## 5. 资产清单模板

各子平台按自己窗口的实际资产填写下表，写到子平台的 `docs/<product_code>-NAS灾备手册.md`：

```markdown
## 我平台备份资产清单

| 类别 | 资产名 | 路径 | 大小 | 重要性 |
|---|---|---|---|---|
| 数据库 | PG lingyao_dev | host=127.0.0.1 db=lingyao_dev | ~119MB | 🔴 |
| 密钥 | JWT secret | geo-backend-py/data/.jwt-secret | <1KB | 🔴 |
| 应用 | jar | backend/target/lingyao-platform.jar | 56MB | 🟡 |
| 静态 | website | website/ | <1MB | 🟡 |
| 静态 | 内置 static | backend/src/main/resources/static/ | <1MB | 🟡 |
| 配置 | application*.yml | backend/src/main/resources/ | <1MB | 🟡 |
```

注：不同子平台数据库可能不同（SQLite / PG / H2），备份方式差异：

| 数据库 | 备份方法 |
|---|---|
| PG | `pg_dump` → `<product_code>_pg.sql.gz` |
| SQLite | 直接拷贝 `<db_name>.db` 文件 |
| H2 file | 直接拷贝 `<db_name>.mv.db` 文件（先停 daemon） |
| H2 mem | 不支持备份（dev 用，不生产） |

---

## 6. 备份脚本模板（参考主站）

主站已实现完整脚本 `scripts/backup_to_nas.sh`，子平台复用其架构思路，**自己实现自己窗口的脚本**（不直接拷贝，按需修改数据库类型、jar 路径、密钥路径）。

### 6.1 主站脚本架构（供借鉴）

主站脚本分 4 步：

```
[1/N] 数据库备份（pg_dump → gz）
[2/N] 应用资产打包（tar -czf 含密钥 + jar + 静态 + 配置）
[3/N] 生成 BACKUP_INFO.txt（含 SHA-256 校验和，显式排除自身避免 hash 错位）
[4/N] rsync 推送到 NAS（av --progress 实时显示）
```

**关键避坑**：

1. **SHA-256 排除 BACKUP_INFO.txt 自身**：`shasum *` 会把刚截断的 INFO_FILE 自身算成空文件 hash。必须显式 `ls | grep -v BACKUP_INFO.txt`
2. **pg_dump 不在 PATH**：主站嵌入 PG 18.6 自带 `data/postgres-18/postgresql-18.6.0-aarch64-apple-darwin/bin/pg_dump`
3. **PGPASSWORD 用环境变量传**：`PGPASSWORD="xxx" pg_dump ...`，不要写脚本里硬编码
4. **保留策略 30 个快照**：超出自动清理（SMB 删隐藏文件可能失败，失败不致命）
5. **软链 latest**：每次备份后 `ln -sfn`，方便恢复时定位

### 6.2 子平台脚本伪代码（自行实现）

```bash
#!/usr/bin/env bash
# <子平台代码> → NAS 备份脚本（参考主站 V2.0.77.8 架构）
# 编写者：<子平台窗口名> · 日期：<YYYY-MM-DD>

set -euo pipefail

PROJECT_DIR="<子平台项目绝对路径>"     # 例：/Users/hua/Documents/myself/AIDD
PRODUCT_CODE="<product_code>"          # 例：aidd
NAS_MOUNT="/Volumes/176XXXX8727"
NAS_BACKUP_BASE="${NAS_MOUNT}/CODE/${PRODUCT_CODE}/backup"
LOCAL_STAGING="/tmp/${PRODUCT_CODE}-backup-staging"

# ── 数据库备份（按子平台数据库类型）──
# PG: PGPASSWORD=xxx pg_dump ... | gzip > ${LOCAL_TMP}/${PRODUCT_CODE}_pg.sql.gz
# SQLite: cp ${DB_FILE} ${LOCAL_TMP}/${PRODUCT_CODE}_sqlite.db
# H2: cp ${DB_FILE} ${LOCAL_TMP}/${PRODUCT_CODE}_h2.mv.db

# ── 应用资产打包 ──
# tar -czf ${LOCAL_TMP}/assets.tar.gz -C ${PROJECT_DIR} \
#   <密钥路径> <jar路径> <静态目录1> <静态目录2> <yml配置>

# ── 备份信息 + SHA-256 ──
# (参考主站 backup_to_nas.sh 的 [3/4] 块)

# ── rsync 推送 ──
# rsync -av ${LOCAL_TMP}/ ${SNAPSHOT_DIR}/

# ── 清理旧快照 ──
# rm -rf ${OLD_SNAPSHOTS}（失败不致命）

# ── latest 软链 ──
# ln -sfn ${TIMESTAMP} ${NAS_BACKUP_BASE}/latest
```

### 6.3 子平台恢复脚本

参考主站 `scripts/restore_from_nas.sh` 的架构：

```bash
# ── [1/4] 拉取备份到本地 staging ──
# ── [2/4] 校验 SHA-256（从 BACKUP_INFO.txt 读 hash 比对实际文件）──
# ── [--dry-run 模式仅校验不写入] ──
# ── [3/4] 备份当前主站到 /tmp/<product_code>-pre-restore-<时间戳>/（防恢复失败兜底）──
# ── [4/4] 实际恢复：解 assets.tar.gz + psql/sqlite 还原 ──
```

恢复脚本必须支持：
- `bash restore_xxx.sh`（恢复 latest）
- `bash restore_xxx.sh 2026-09-08_20-30-00`（恢复指定快照）
- `bash restore_xxx.sh --dry-run latest`（仅校验不写入）

---

## 7. 子平台接入 Checklist

每个子平台窗口在接入时，请按此清单逐项确认并写入 `<子平台>/CHANGELOG.md`：

- [ ] 1. 已读本文档 + 主站 `凌瑶/docs/凌瑶主站-NAS灾备手册.md`
- [ ] 2. macOS SMB 已挂载到 `/Volumes/176XXXX8727`（`mount | grep lingyao`）
- [ ] 3. 在 `CODE/<product_code>/` 下建 `backup/` 子目录（owner=hua）
- [ ] 4. 写 `<子平台>/scripts/backup_to_nas.sh`（参考主站架构）
- [ ] 5. 写 `<子平台>/scripts/restore_from_nas.sh`（支持 --dry-run）
- [ ] 6. 跑首次备份 → 验证 latest 软链创建
- [ ] 7. 跑 `restore_xxx.sh --dry-run latest` → 校验 SHA-256 通过
- [ ] 8. 写 `<子平台>/docs/<product_code>-NAS灾备手册.md`（资产清单 + 操作步骤）
- [ ] 9. 在子平台 `CHANGELOG.md` 写"接入 NAS 灾备"章节（参考主站 V2.0.77.8）
- [ ] 10. **通知主站窗口**（本窗口 `凌瑶/`）：在共享 CHANGELOG `~/.workbuddy/memory/lingyao-space/SHARED_CHANGELOG.md` 追加接入记录

---

## 8. 隔离与冲突避免

### 8.1 NAS 资源隔离

- 每个子平台写自己 `CODE/<product_code>/backup/` 目录，**互不影响**
- 软链 `latest` 在各自目录内独立维护
- 备份脚本并行运行无冲突（不同时间戳目录名）

### 8.2 NAS 存储容量监控

- 极空间 Z4 Pro 系统盘 9.5G（系统）+ 数据盘 15-122T（mergerfs 合并）
- 定期 `df -h /Volumes/176XXXX8727` 检查剩余空间
- 单子平台 L2 全量备份 ~200MB / 每日增量 <10MB
- 30 天保留 × 200MB = 6GB / 子平台 → 7 个子平台总计 <50GB，**完全够用**

### 8.3 macOS 账户统一

- 当前 NAS SMB 凭据绑定主站 macOS 用户 `hua`
- 各子平台在同一台 macOS 上跑脚本，**共享同一 SMB 凭据**（存 macOS Keychain）
- 若未来子平台跑在不同机器，需各自在 macOS Keychain 存 SMB 凭据

---

## 9. 故障排查（通用）

| Q | 现象 | 排查 |
|---|---|---|
| Q1 | `NAS 未挂载` | Finder 连 `smb://192.168.3.60` → 选 `176XXXX8727` → 登录 |
| Q2 | 数据库备份失败 | 检查 daemon 状态：`lsof -iTCP:<port> -P` 确认端口监听 |
| Q3 | 写入 NAS 失败 | 检查挂载点：`ls -la /Volumes/176XXXX8727/CODE/` 确认目录可写 |
| Q4 | SHA-256 校验失败 | 可能是 SMB 写入不完整，重跑备份；如持续失败，检查磁盘 |
| Q5 | rm 旧快照失败 | SMB 协议限制，去 Finder 手动清理；或保留稍长时间不清理 |
| Q6 | `pg_dump: command not found` | 系统 PATH 无 pg_dump，需用嵌入 PG 自带的（路径见主站手册）|

---

## 10. 升级到 L3（异地云端容灾）

子平台如需要更高级别灾备，按下面路径升级：

1. 注册云服务（七牛云 / 阿里云 OSS / AWS S3）
2. 在备份脚本末尾追加云端推送：
   ```bash
   # 七牛云例：qshell rput <bucket> /backup/$(date +%Y%m%d)/${TIMESTAMP}.tar.gz ${SNAPSHOT_DIR}.tar.gz
   # 阿里云 OSS 例：ossutil cp ${SNAPSHOT_DIR}/ oss://<bucket>/${PRODUCT_CODE}/ --recursive
   ```
3. 备份保留延长到 90 天（云端成本考虑）
4. 月度自动恢复演练（cron 跑 dry-run，校验完整性）

**L3 当前主站暂未启用**（主人考虑成本暂缓），子平台如预算充足可独立评估。

---

## 11. 联系主站窗口

子平台接入过程中如遇到以下情况，联系主站窗口（本窗口 `凌瑶/`）：
- NAS 物理故障（断电、硬盘坏）
- NAS 容量规划（总备份占用超过 50%）
- macOS SMB 凭据失效（所有平台同时无法挂载）
- L3 升级需统一协调云服务商选型

---

## 📝 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-08 | 初版（基于主站 V2.0.77.8 落地经验沉淀） |

---

## ✅ 主站窗口承诺

主站承诺：
- 本规范长期维护（随主站实践更新）
- NAS 资源持续可用（极空间 Z4 Pro 主人承诺不出问题）
- 共享 CHANGELOG `~/.workbuddy/memory/lingyao-space/SHARED_CHANGELOG.md` 持续追加接入记录
- 子平台接入过程如遇 NAS 相关问题，主站窗口协助排查

---

**子平台窗口收到本文档后，请在子平台 CHANGELOG.md 写"已读 + 计划接入日期"，并回主站窗口备案。**
