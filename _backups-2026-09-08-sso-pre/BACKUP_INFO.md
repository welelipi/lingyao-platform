# 主站 SSO 配置模块 · 实施前灾备快照

**时间**：2026-09-08 20:41 GMT+8
**触发**：主人 2026-09-08 20:40 下令"按 SSO 两份 PRD,进行灾备"
**当前版本**：V2.0.77.9（主站业务版本号未变，后端 pom.xml `<version>1.0.0</version>`）
**范围**：凌瑶主站本地关键资产（实施 M1-S1 前的基线）
**参考**：`_backups-2026-09-01-sso-config/`（9-01 SSO 历史灾备命名规范）+ V2.0.77.8 NAS 灾备脚本

---

## 1. 备份清单

| # | 文件 | 原始大小 | 压缩后 | SHA-256（截断前 16B） |
|---|---|---|---|---|
| 1 | `backend-src.tar.gz` | 60M | 306 KB | `ea646cb3c0d70dae` |
| 2 | `backend-resources.tar.gz` | — | 137 KB | `76f0039efbabb8c3` |
| 3 | `website.tar.gz` | 636K | 151 KB | `e19717ca1e79212a` |
| 4 | `scripts.tar.gz` | 156K | 37 KB | `580eb458fd4d6f7b` |
| 5 | `docs.tar.gz` | 276K | 87 KB | `e3e62b1c3121a912` |
| 6 | `CHANGELOG.md` | 176K | 178 KB | `c8bd07b25828437d` |
| 7 | `application.yml` | 6.4K | 6.4 KB | `d5cf41c5ee66eb8b` |

**总大小**：896 KB（压缩后）/ ~61 MB（原始合计）

---

## 2. 备份内容明细

### `backend-src.tar.gz`
凌瑶主站 Java 后端源码（Spring Boot）：
- `controller/`、`service/`、`entity/`、`repository/`、`dto/`、`config/`、`security/`、`util/`、`exception/`、`filter/`
- 不含 `target/`（编译产物由 V2.0.77.8 NAS 灾备覆盖）

### `backend-resources.tar.gz`
后端资源文件：
- `static/`（前端编译后产物，已 cp 自 `website/`，jar 内真源）
- `templates/`（如有）
- `application*.yml` 多环境配置

### `website.tar.gz`
前端源码（待 cp 到 backend `static/` + mvn package）：
- `portal.html`、`company-admin.html`、`admin.html`、`login.html`、`index.html` 等
- `static/`、`css/`、`js/`

### `scripts.tar.gz`
运维脚本：
- `start_backend_daemon.py`、`backup_to_nas.sh`、`restore_from_nas.sh`、`stop_backend.sh` 等

### `docs.tar.gz`
项目文档：
- 3 份 NAS 灾备文档（V2.0.77.8/V2.0.77.9 产物）
- 其他主站设计/运维文档

### `CHANGELOG.md`
主站版本变更日志（截至 V2.0.77.9，2755 行）。

### `application.yml`
主配置（prod 视角，Spring profile）。

---

## 3. 为什么**只做本地快照，不重跑 NAS 灾备**？

| 因素 | 说明 |
|---|---|
| **时间** | V2.0.77.8 NAS 灾备仅 10 分钟前（20:30）执行过 |
| **改动** | 实施前快照，主站代码未做任何变更 |
| **NAS 容量** | 7 平台 × 50 GB 配额（V2.0.77.9 规划），每次全量会浪费 NAS 配额 |
| **NAS 策略** | V2.0.77.8 主人拍板"手动触发，不上 cron" |
| **触发时机** | 等 SSO M1 实施完、CHANGELOG 升到 V3.0.x 后再冲 NAS |

---

## 4. 回滚 SOP（如 SSO 实施出现不可逆问题）

### 4.1 准备

```bash
cd /Users/hua/Documents/myself/凌瑶

# 1. 停后端（V2.0.77.x 守护进程）
launchctl unload ~/Library/LaunchAgents/com.lingyao.backend.plist
# 或
ps aux | grep java | grep lingyao | awk '{print $2}' | xargs kill

# 2. 备份当前错误状态到另一目录（避免覆盖本快照）
mkdir -p _backups-2026-09-08-sso-rollback-FAILED
cp -R backend website scripts docs _backups-2026-09-08-sso-rollback-FAILED/
```

### 4.2 解压快照

```bash
# 3. 清空当前主站代码（保留 .git/ 与 _backups-*/）
rm -rf backend website scripts docs

# 4. 解压本快照
tar -xzf _backups-2026-09-08-sso-pre/backend-src.tar.gz
tar -xzf _backups-2026-09-08-sso-pre/backend-resources.tar.gz
tar -xzf _backups-2026-09-08-sso-pre/website.tar.gz
tar -xzf _backups-2026-09-08-sso-pre/scripts.tar.gz
tar -xzf _backups-2026-09-08-sso-pre/docs.tar.gz

# 5. SHA-256 校验（可选）
cd _backups-2026-09-08-sso-pre
shasum -a 256 -c SHA256SUMS.txt
```

### 4.3 验证 + 启动

```bash
cd /Users/hua/Documents/myself/凌瑶

# 6. 重新打包 jar（因为 jar 内 static 是真源）
cd backend && mvn clean package -DskipTests && cd ..

# 7. 启动后端
LINGYAO_PROFILE=dev-pg python3 scripts/start_backend_daemon.py

# 8. 验证
curl http://localhost:9091/api/_diag/version
```

---

## 5. 实施进度记录

| 阶段 | 状态 | 时间 |
|---|---|---|
| ✅ 灾备快照（本文件） | 完成 | 2026-09-08 20:41 |
| ⏳ M1-S1：建表 DDL + KMS 工具 | 待主人开工 | — |
| ⏳ M1-S2：5 Provider 抽象 + 4 实现 | 待主人开工 | — |
| ⏳ M1-S3：SSO 配置 CRUD + UI Tab | 待主人开工 | — |
| ⏳ M1-S4：OAuth 跳转 + 回调 + 专属链接 | 待主人开工 | — |
| ⏳ M1-S5：portal.html 复制按钮 + JWT 新 claim | 待主人开工 | — |
| ⏳ 推送主站 + 通知 5 子平台 | 待主人拍板 | — |

---

## 6. 联系 & 反馈

- **备份执行**：本地主流程窗口（主站窗口）
- **PRD**：`/tmp/MainSite_sso-config_PRD.md` + `/tmp/MainSite_sso-subplatform-requirements.md`
- **NAS 灾备**：V2.0.77.8 链路已通（极空间 Z4 Pro 192.168.3.60 SMB 挂载）
- **反馈渠道**：在本窗口对话里 @「本地主流程」

---

*花卷 · 2026-09-08 20:41*
