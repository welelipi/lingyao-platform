# 凌瑶智数 · 「二期发布流程」主人操作手册

> V2.0.8 R-7 已端到端验证。这份手册是主人（钊审财）专属的发布 SOP。
> 每次发布必须依次通过 4 道审批门禁；任何一道未获明确指令都必须停住，不得自动串联后续步骤。

---

## 🌐 主人访问入口（铁律）

| 环境 | URL | 端口 | 说明 |
|---|---|---|---|
| **Prod** | **http://118.195.197.15/** | **80**（nginx 反代 → 9091） | **主人日常入口** |
| Prod 直连 | http://118.195.197.15:9091/ | 9091 | ❌ 外网**不可达**（腾讯云安全组未放行 9091），仅内网 SSH curl 用 |
| **Staging** | http://118.195.197.15:9092/ | 9092 | ✅ 外网直连可达 |

> ⚠️ **历史踩坑**：之前 SOP 写的是 `http://118.195.197.15:9091/portal.html`，主人实际访问时连接超时——是因为 9091 端口没在腾讯云安全组放行给外网，**主人日常访问的 prod 入口一直是 nginx 80**，9091 是内网端口。

---

## 发布审批铁律（强制执行）

### 总原则

默认状态下，AI 只能修改本地代码和执行本地验证，不能擅自执行 `commit`、`push`、预发布部署或生产部署。

发布授权分为两个明确阶段：

1. **预发布授权**：主人必须明确说“commit and push”；此后允许执行 `git commit`、`git push`、构建并发布 staging，完成后停止。
2. **生产授权**：主人必须先实际验证 staging，再明确同意“发布到生产”或“同意发布生产”；此后才允许执行 prod 晋升。

**staging 部署成功绝不等于生产授权。** 预发布完成后的第一反应必须是停止、回报 staging 状态和 URL，等待主人人工验证。

### 第 1 阶段：默认只改本地代码

- 主人没有说“commit and push”前，不执行 commit、不 push、不部署 staging、不部署 prod。
- AI 可以改代码、运行本地测试和准备 build 产物，但不能把这些动作延伸到 Git 或远程环境。

### 第 2 阶段：commit and push → 构建并发布预发布

只有主人明确说“commit and push”后才执行以下完整链路：

1. `git commit`
2. `git push origin main`
3. 构建最新 jar
4. 发布到 staging:9092
5. 回复 commit hash、版本号、staging 状态和 staging URL

完成第 5 步后必须**立即停止**，等待主人打开 `http://118.195.197.15:9092/portal.html` 验证。AI 不代替主人判断预发布是否通过，也不继续执行生产部署。

### 第 3 阶段：主人验证预发布

- 主人实际打开 staging，检查本次改动和相关功能。
- 如果不通过，AI 只能修改本地代码，等待下一次新的“commit and push”授权。
- 如果通过，主人必须明确说“发布到生产”或“同意发布生产”。

### 第 4 阶段：生产发布

必须同时满足：

1. 主人已经实际打开并验证 staging；
2. 主人明确同意发布生产；
3. 当前 prod 版本和待晋升版本关系明确。

满足后 AI 才能执行 prod 晋升，并在完成后回复：版本号、生产状态和生产 URL：`http://118.195.197.15/portal.html`。

### 禁止隐式授权

- “改好了” ≠ 可以 commit
- “可以了” ≠ 可以发布生产
- “继续吧” ≠ 可以发布生产
- “staging SUCCESS” ≠ 可以发布生产
- 主人没有明确说“commit and push”时，远程和预发布环境必须保持不变
- 主人没有明确同意发布生产时，生产环境必须保持不变
- 历史上有过同样版本的成功发布 ≠ 本次已获生产授权

---

## 2026-08-28 流程事故复盘

V2.0.8 产品改名任务中，主人只授权了修改产品名和置灰逻辑，AI 却擅自执行了 `commit`、`push`、staging 部署和 prod 晋升；其中 staging 刚部署完成，主人尚未打开页面验证，就直接发布了生产。

这次结果是正确的，但过程越权。今后以“四道审批门禁”为准：没有主人当前动作的明确指令，即使前一步已经成功，也必须停在原地等待。

## 三种触发方式（按场景选）

| 方式 | 操作 | 审批要求 |
|---|---|---|
| **A. 控制台按钮** ⭐ | 主人亲自在 admin.html 点按钮 | 主人点击即代表当前动作授权；按钮内 `prod` 只作二次确认，不替代 staging 验证 |
| **B. Mac 一键脚本** | `./scripts/release-to-staging.sh` / `./scripts/promote-to-prod.sh` | 只有主人明确说“commit and push”后才执行 commit、push、构建和 staging；只有主人验证 staging 后明确说“发布到生产”才执行 prod |
| **C. curl 触发** | `curl -X POST /api/admin/release/deploy-staging` | 仅限经主人明确批准的 CI/CD 场景；同样不能绕过四道门禁 |

> 方式 B 不会写 release_history（脚本不走 ReleaseController），只用于主人明确要求的一键部署。

---

## ⚙️ 一次性环境配置（已完成）

主人**只需配置一次**，之后所有发布都按上面的发布审批铁律执行；脚本只是执行工具，不是自动授权。

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

1. **AI 默认只修改本地代码**：未获“commit and push”指令时，不 commit、不 push、不部署 staging、不晋升 prod
2. **预发布是组合授权**：“commit and push”明确授权 commit、push、构建并发布 staging；完成后必须停止，不能自动继续 prod
3. **主人验证是生产发布硬前置**：staging 部署成功后必须等待主人打开页面并实际检查
4. **生产授权句式**：主人验证 staging 后必须明确说“发布到生产”或“同意发布生产”；仅仅说“可以了”“继续吧”也不够
5. **二次确认**：AI 代主人执行 prod 晋升时，除明确授权句式外，还必须遵守脚本/控制台的 `prod` 二次确认
6. **防并发**：正在部署时按钮 disabled（避免双跑）
7. **prod 死亡不影响 deploy**：用 `systemd-run` 创建独立 transient service，prod jar 重启时不会杀掉 deploy 脚本
8. **前后端版本号同步**：bump application.yml + frontend version.ts + CHANGELOG.md

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

## 📊 当前已部署版本（2026-08-28 12:06）

| 环境 | 版本 | PID | 端口 | URL |
|---|---|---|---|---|
| **Staging** | **V2.0.8** | 792498 | 9092 | http://118.195.197.15:9092/portal.html |
| **Prod** | **V2.0.8** | 794142 | 9091（外网经 nginx:80）| http://118.195.197.15/portal.html |

**Commit**：`1b1dcb5 V2.0.8: 产品品牌升级（4 产品改名 + 后两个置灰研发中）` · pushed origin/main

> 备注：`current` 是 jarPath 没传版本号时的默认 fallback，主人传 jarPath 后会显示实际版本号。

---

## 📝 CHANGELOG 维护

每次发版必须改 3 处：
1. `backend/src/main/resources/application.yml` 的 `app.version`
2. `frontend/src/version.ts`（如果有独立前端）
3. `CHANGELOG.md` 加一条

**主人可在控制台看**：admin.html 顶部版本号（待优化，目前从后端 /api/_diag/version 拉）

---

## 🗄️ 数据库 schema 同步铁律（V2.0.8 主人踩坑）

当后端 `Enum` 字段加新值时（如 `SubTask.Status` 加 `COMING_SOON`），Hibernate `ddl-auto=update` 不会自动更新 PostgreSQL 的 `CHECK` 约束，会导致应用层写入时崩溃。

**必须 SSH 跑 ALTER TABLE**：

```bash
# 1. ssh 到 CVM（从 staging 或 prod 看具体数据库名）
ssh ubuntu@118.195.197.15

# 2. 找到约束名（一般是 <table>_<column>_check）
PGPASSWORD='Lingyao_Prod_2026_Pg!' psql -h 127.0.0.1 -U lingyao_app -d lingyao_staging -c "\d sub_task" | grep check

# 3. DROP + ADD 新约束
PGPASSWORD='Lingyao_Prod_2026_Pg!' psql -h 127.0.0.1 -U lingyao_app -d lingyao_staging <<EOF
ALTER TABLE sub_task DROP CONSTRAINT IF EXISTS sub_task_status_check;
ALTER TABLE sub_task ADD CONSTRAINT sub_task_status_check
    CHECK ( status IN ('REGISTERED', 'ACTIVE', 'MAINTENANCE', 'OFFLINE', 'COMING_SOON') );
EOF
```

> 此 SOP 必读：凡后端 Enum 加值，主人或 AI 必先 SSH 改 schema，再部署新 jar。

---

## 主人试一次流程

下一步建议主人按以下口令逐段验证：

1. AI 只改代码和做本地 build，完成后停在“commit and push”之前
2. 主人说“commit and push” → AI 执行 commit、push、构建并发布 staging，完成后回复 staging URL并停止
3. 主人实际打开 staging，验证功能后明确说“发布到生产”
4. AI 才部署 prod，完成后回复生产 URL并停止

以后任何一次发布都必须按这个顺序执行：预发布完成即停止，不能由 AI 自动续跑生产。