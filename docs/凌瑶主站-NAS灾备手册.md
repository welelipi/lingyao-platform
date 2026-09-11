# 凌瑶智数 · 主站 → 极空间 Z4 Pro NAS 灾备手册

> **创建时间**：2026-09-08（V2.0.77.8）
> **链路**：主站 macOS → SMB 挂载 → 极空间 Z4 Pro（192.168.3.60）
> **备份等级**：L2 中级（PG 库 + JWT secret + 应用 jar + 静态资源 + 配置）
> **触发方式**：手动（无 cron 定时）

---

## 📋 总览

```
【主站 macOS】                              【极空间 Z4 Pro】
                                                   
bash scripts/backup_to_nas.sh                 
   ↓                                           
1. pg_dump lingyao_dev 库                     
2. 打包 JWT secret + jar + static + yml       
3. rsync 推送到 SMB 挂载点                    
   ↓                                           
NAS SMB 自动挂载（macOS Bonjour）             
   ↓                                           
/Volumes/176XXXX8727/CODE/lingyao-backup/      
   ├── 2026-09-08_20-26-57/                   
   │   ├── pg_lingyao_dev.sql.gz (12KB)       
   │   ├── assets.tar.gz (51MB)               
   │   └── BACKUP_INFO.txt（含 SHA-256）       
   ├── 2026-09-08_20-24-51/                   
   ├── 2026-09-08_20-21-21/                   
   └── latest -> 2026-09-08_20-26-57          
```

---

## 🚀 一、初始设置（已完成）

### 1.1 NAS 端 SSH 开启

主人已在极空间后台开启 SSH，端口 **10000**。

如果新设备接入需要重新配置：
1. 浏览器登录 `http://192.168.3.60:5050/`（或 `http://192.168.3.60:5055/`）
2. 设置 → 系统设置 → SSH → 开启
3. 端口保持 10000

### 1.2 macOS SMB 自动挂载

macOS 通过 Bonjour 自动发现 NAS 并挂载到 `/Volumes/176XXXX8727`（极空间 SMB 协议层对用户名中间 4 位打码）。

如未自动挂载：
- Finder → 前往 → 连接服务器 → `smb://192.168.3.60`
- 选 `176XXXX8727` 共享，输入账号 17611378727 + 密码
- macOS 自动存 Keychain，下次开机自动重连

### 1.3 NAS 端备份目录

- **路径**：`/tmp/zfuse/17611378727/CODE/lingyao-backup/`（SSH 视角）
- **SMB 视角**：`/Volumes/176XXXX8727/CODE/lingyao-backup/`（macOS Finder 视角）
- 已在 `CODE/` 下建 `lingyao-backup/` 子目录

---

## 🔄 二、日常备份（手动触发）

### 触发命令

```bash
bash /Users/hua/Documents/myself/凌瑶/scripts/backup_to_nas.sh
```

### 备份内容（L2 中级）

| 资产 | 来源路径 | 说明 |
|---|---|---|
| **PG 数据库** | `data/postgres-18/.../bin/pg_dump` → `pg_lingyao_dev.sql.gz` | 全量 + schema + data |
| **JWT secret** | `geo-backend-py/data/.jwt-secret` | 凌瑶 SSO 加密密钥 |
| **应用 jar** | `backend/target/lingyao-platform.jar` | Spring Boot 主 jar |
| **静态资源 1** | `website/` | 官网前端 HTML/CSS/JS |
| **静态资源 2** | `backend/src/main/resources/static/` | jar 内置静态资源 |
| **配置 yml** | `backend/src/main/resources/application*.yml` | 3 个 profile 配置 |
| **CHANGELOG** | `CHANGELOG.md` | 版本历史 |

### 备份保留

- 保留最近 **30 个快照**（按时间目录数计）
- 超限自动清理（受 SMB 删除限制可能失败，需 Finder 手动清理）

---

## 🔁 三、恢复演练

### 3.1 仅校验（推荐日常）

```bash
bash /Users/hua/Documents/myself/凌瑶/scripts/restore_from_nas.sh --dry-run
```

输出：
- ✅ assets.tar.gz 校验通过
- ✅ pg_lingyao_dev.sql.gz 校验通过

### 3.2 真实恢复（紧急情况）

```bash
# Step 1：停主站 daemon（恢复 PG 期间不能有连接）
lsof -iTCP:9091 -P | grep LISTEN | awk '{print $2}' | xargs kill -9

# Step 2：恢复 latest 快照
bash /Users/hua/Documents/myself/凌瑶/scripts/restore_from_nas.sh

# 或恢复指定快照
bash /Users/hua/Documents/myself/凌瑶/scripts/restore_from_nas.sh 2026-09-08_20-26-57

# Step 3：重启 daemon
cd /Users/hua/Documents/myself/凌瑶 && LINGYAO_PROFILE=dev-pg python3 scripts/start_backend_daemon.py

# Step 4：验证
curl http://localhost:9091/api/_diag/version
```

### 3.3 恢复前自动备份

脚本会自动把当前主站资产备份到 `/tmp/lingyao-pre-restore-snapshot-<时间戳>/`，如恢复失败可手动 rollback。

---

## 🛠️ 四、故障排查

### Q1：备份失败 "NAS 未挂载"
A：Finder 打开 → 前往 → 连接服务器 → `smb://192.168.3.60` → 登录

### Q2：备份失败 "PG 备份失败"
A：dev-pg daemon 未起。执行 `cd /Users/hua/Documents/myself/凌瑶 && LINGYAO_PROFILE=dev-pg python3 scripts/start_backend_daemon.py`

### Q3：恢复后 daemon 起不来
A：检查 `/tmp/lingyao-pre-restore-snapshot-<时间戳>/` 有当前主站备份。回滚：`cp /tmp/lingyao-pre-restore-snapshot-*/geo-backend-py/data/.jwt-secret /Users/hua/Documents/myself/凌瑶/geo-backend-py/data/`

### Q4：SMB 删除失败（"Operation not permitted"）
A：极空间 SMB 协议限制。文件可写但不能 rm。手动清理：在 Finder 选中 lingyao-backup/ 下旧快照目录，按 Cmd+Delete（移到废纸篓）

### Q5：备份目录 `/Volumes/176XXXX8727/` 消失
A：macOS 重启或 NAS 离线后未自动重连。Finder 重新连接即可

---

## 📝 五、版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| V2.0.77.8 | 2026-09-08 | 初版建立（NAS 极空间 Z4 Pro · L2 中级 · 手动触发）|

---

## 🔐 六、安全说明

- **极空间账户密码**（`Huajuan****`）仅在 macOS Keychain 存 SMB 凭据，**不入 git**、**不入脚本**
- **SMB 挂载点** owner=hua 700/755，**只主站 macOS 用户可写**
- **SSH 密钥** 备份专用 `~/.ssh/id_ed25519_nas_backup`（未启用，因走 SMB 不需要 SSH）
- **备份文件** 在 NAS CODE/lingyao-backup/ 下，仅主站 macOS 账号可读写
- **JWT secret** 是最敏感资产（密钥丢 = 全部 token 失效），每份备份必含

---

## 🛡️ 七、未来升级路径

当主人准备升级到 L3（异地容灾）时：
1. 注册七牛云 / 阿里云 OSS
2. 在 `scripts/backup_to_nas.sh` 末尾追加 `qshell rput ...` 或 `ossutil cp ...`
3. 保留本地 NAS + 加异地云端 → L3
