# 凌瑶智数 · 「二期发布流程」主人操作手册

> V2.0.7 R-7 已端到端跑通。这份手册是主人（钊审财）专属的发布 SOP。
> 每次发布只需 5 步，30-90 秒走完全链路。

---

## 🌐 主人访问入口（铁律）

| 环境 | URL | 端口 | 说明 |
|---|---|---|---|
| **Prod** | **http://118.195.197.15/** | **80**（nginx 反代 → 9091） | **主人日常入口** |
| Prod 直连 | http://118.195.197.15:9091/ | 9091 | ❌ 外网**不可达**（腾讯云安全组未放行 9091），仅内网 SSH curl 用 |
| **Staging** | http://118.195.197.15:9092/ | 9092 | ✅ 外网直连可达 |

> ⚠️ **历史踩坑**：之前 SOP 写的是 `http://118.195.197.15:9091/portal.html`，主人实际访问时连接超时——是因为 9091 端口没在腾讯云安全组放行给外网，**主人日常访问的 prod 入口一直是 nginx 80**，9091 是内网端口。

---

## 🚀 主人日常发布流程（5 步）

### Step 1: Mac 改代码 + build

```bash
cd /Users/hua/Documents/myself/凌瑶
# 改代码...
mvn clean package -DskipTests -B -f backend/pom.xml
ls -la backend/target/lingyao-platform.jar  # 确认 jar 生成（~57 MB）
```

> 注意：`mvn clean package` 必须在 `backend/pom.xml` 路径下（或用 `-f backend/pom.xml`），项目根目录没有 pom.xml。

### Step 2: push 代码到 GitHub

```bash
git add -A
git commit -m "V2.0.x: <你的改动描述>"
git push origin main
```

### Step 3: 控制台点「发布 staging」按钮

1. 打开 **http://118.195.197.15/admin.html** （admin / admin123）
2. 点「🚀 发布管理」Tab
3. 点「🧪 发布 staging」按钮
4. 输入 jar 路径（如 `/Users/hua/Documents/myself/凌瑶/backend/target/lingyao-platform.jar`）→ 点确认
5. 等 60-90 秒，弹窗提示 SUCCESS

> **底层流程**：控制台按钮 → 后端 ReleaseService.initStagingDeploy 保存 RUNNING → executeStagingDeploy SSH 到 staging 机器跑 release-to-staging.sh → 重启 staging service → 健康检查 → 写 SUCCESS

### Step 4: 验证 staging

打开 **http://118.195.197.15:9092/portal.html** （admin / Staging_Admin_2026_Pg!）→ 检查功能是否生效

### Step 5: 点「晋升生产」按钮

1. 回到 admin.html → 发布管理 Tab
2. 点「🚀 晋升生产」按钮
3. 弹窗要求输 `prod` 二次确认 → 输入 `prod` → 点确认
4. 等 60-90 秒，弹窗提示 SUCCESS

> **底层流程**：控制台按钮 → 后端 ReleaseService.initProdPromote 保存 RUNNING → executeProdPromote 用 systemd-run 创建独立 transient service 跑 deploy-prod.sh → 重启 prod service → 健康检查 → 写 SUCCESS

---

## 🔧 三种触发方式（按场景选）

| 方式 | 操作 | 适用场景 |
|---|---|---|
| **A. 控制台按钮** ⭐ | admin.html 点按钮 | 日常发布（推荐）|
| **B. Mac 一键脚本** | `./scripts/release-to-staging.sh` / `./scripts/promote-to-prod.sh` | 不开浏览器时 |
| **C. curl 触发** | `curl -X POST /api/admin/release/deploy-staging` | CI/CD 自动化 |

> 注意：方式 B 不会写 release_history（脚本不走 ReleaseController），仅用于"快速跑通"场景。

---

## ⚙️ 一次性环境配置（已完成）

主人**只需配置一次**，之后所有发布都用上面的 5 步流程。

### Mac 本地 release SSH key（一劳永逸）

> ⚠️ **2026-08-28 V2.0.7 发现**：Mac 本地 `~/.ssh/release_staging_key` 私钥文件被系统清理丢失，导致 `release-to-staging.sh`/`deploy-staging.sh` 找不到 key 会 fail。**V2.0.7 已重新生成**，下面的命令主人也可手动重跑：

```bash
# 1. Mac 本地生成 ed25519 keypair
ssh-keygen -t ed25519 -f ~/.ssh/release_staging_key -N "" -C "release_staging_$(date +%Y%m%d)" <<< ""

# 2. 把 pubkey 推到 CVM（任何能连的 key 都行，这里用默认 id_ed25519）
scp -i ~/.ssh/id_ed25519 ~/.ssh/release_staging_key.pub ubuntu@118.195.197.15:/tmp/release_staging_key.pub
ssh -i ~/.ssh/id_ed25519 ubuntu@118.195.197.15 "
cat /tmp/release_staging_key.pub >> ~/.ssh/authorized_keys &&
chmod 600 ~/.ssh/authorized_keys &&
sudo mkdir -p /root/.ssh &&
sudo cp /home/ubuntu/.ssh/release_staging_key /root/.ssh/release_staging_key &&
sudo chmod 600 /root/.ssh/release_staging_key"

# 3. 测试新 key 直连（不依赖默认 key）
ssh -i ~/.ssh/release_staging_key -o BatchMode=yes ubuntu@118.195.197.15 'echo OK'

# 4. ssh-agent 加载（可选，方便脚本不显式 -i）
ssh-add ~/.ssh/release_staging_key
```

### CVM 上 release SSH key（已配）

**已完成**（V2.0.6 在 2026-08-27 配，V2.0.7 在 2026-08-28 重新生成 + push）：
- CVM ubuntu：`/home/ubuntu/.ssh/release_staging_key`（私钥）+ authorized_keys 含对应公钥
- CVM root：`/root/.ssh/release_staging_key`（私钥，prod jar 是 root 跑）
- 远程 pubkey comment：`release_staging_20260828`

### 配置文件覆盖

`/opt/lingyao/application-private.yml` 末尾追加：
```yaml
lingyao:
  release:
    staging:
      ssh-key-path: /root/.ssh/release_staging_key
```

### CVM 脚本

部署脚本传到 CVM（已 2026-08-27 完成）：
- `/opt/lingyao/staging/release-to-staging.sh`（staging 路径）
- `/opt/lingyao/staging/deploy-staging.sh`
- `/opt/lingyao/deploy-prod.sh`

---

## ⚠️ 铁律（必须遵守）

1. **「晋升生产」必须先 staging 验证通过**：前端按钮和后端 API 都强制 enforce
2. **二次确认**：晋升生产必须输入 `prod` 才能点确认
3. **防并发**：正在部署时按钮 disabled（避免双跑）
4. **prod 死亡不影响 deploy**：用 `systemd-run` 创建独立 transient service，prod jar 重启时不会杀掉 deploy 脚本
5. **前后端版本号同步**：bump application.yml + frontend version.ts + CHANGELOG.md

---

## 🐛 故障排查

| 现象 | 原因 | 修复 |
|---|---|---|
| 按钮变 ⏳ 部署中卡住 | ssh 链路问题 | 看 release_history 日志（点"📜 日志"按钮）|
| 历史显示 exit 143 SIGTERM | systemd-run 创建 service 失败 | `systemctl list-units --type=service --all \| grep run-` 看 transient service 状态 |
| 健康检查 404 | jar 损坏 | 看 prod/staging logs：`/opt/lingyao/logs/backend.out` |
| 4 子产品页部分 200 | 部署成功但部分页面 bug | 看具体哪个页 curl 404 |
| staging 重启后 prod 不通 | 部署把 prod 也重启了（不应发生）| 检查 deploy-staging.sh 是否被改错 |

---

## 📊 当前已部署版本（2026-08-28 10:46）

| 环境 | 版本 | PID | 端口 | URL |
|---|---|---|---|---|
| **Staging** | **V2.0.7** | 770279 | 9092 | http://118.195.197.15:9092/portal.html |
| **Prod** | **V2.0.7** | 771156 | 9091（外网经 nginx:80）| http://118.195.197.15/portal.html |

**Commit**：`c35e0dc V2.0.7: 端到端发布流程验证 (R-7)` · pushed origin/main

> 备注：`current` 是 jarPath 没传版本号时的默认 fallback，主人传 jarPath 后会显示实际版本号。

---

## 📝 CHANGELOG 维护

每次发版必须改 3 处：
1. `backend/src/main/resources/application.yml` 的 `app.version`
2. `frontend/src/version.ts`（如果有独立前端）
3. `CHANGELOG.md` 加一条

**主人可在控制台看**：admin.html 顶部版本号（待优化，目前从后端 /api/_diag/version 拉）

---

## 🎯 主人试一次流程

下一步建议主人自己走一遍：

1. 改一行代码（比如 admin.html 顶部加一句注释）
2. build + push + 控制台点 staging 按钮
3. 验证 staging
4. 控制台点晋升按钮 + 输 prod
5. 验证 prod
6. 看 release_history 多了 2 条 SUCCESS

走一遍后心里就有底了，整个二期流程完全跑通。