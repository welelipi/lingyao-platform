# 凌瑶智数 · 发布流程手册（V2.0.5 R-7）

> **目的**：让主人和团队成员能"端到端"地发布凌瑶智数 jar 到 staging / prod，不靠记忆、不靠 SSH 经验。

---

## 🎯 一图看懂

```
┌──────────┐    push     ┌──────────┐   自动 build    ┌──────────┐
│ 研发改代码 ├─────────────►│ GitHub  │ ─────────────► │ 本地 jar │
└──────────┘              └──────────┘                └──────────┘
                                                            │
                          ┌─────────────────────────────────┘
                          │  Mac 本地 mvn package
                          ▼
                  ┌─────────────────┐
                  │  1. 上传 jar    │  Mac → CVM (scp)
                  │  2. 备份旧 jar  │  CVM cp
                  │  3. systemd 重启│  systemctl restart
                  │  4. 健康检查    │  curl /api/health
                  └─────────────────┘
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
    ┌──────────────┐            ┌──────────────┐
    │  Staging 9092 │            │  Prod 9091   │
    │  (验证环境)    │   ←晋升→   │  (生产环境)   │
    └──────────────┘            └──────────────┘
```

---

## 🚀 三种发布方式（按场景选）

### 方式 A：控制台按钮（推荐 · 最常用）

**入口**：浏览器打开 `http://118.195.197.15/admin.html`（生产）或 `http://118.195.197.15:9092/admin.html`（staging）

**步骤**：
1. 用**平台超管**账号登录（默认 admin/admin123，首次登录强制改密）
2. 进入「🚀 发布管理」Tab
3. 点「📦 发布 staging」 → 输入 jar 绝对路径 → 确认
4. 等 60-90 秒看状态（自动刷新 5s/35s）
5. 验证 staging：`curl http://118.195.197.15:9092/api/_diag/version`
6. 点「⬆ 晋升生产」 → 二次确认输入 `prod` → 确认
7. 验证 prod：`curl http://118.195.197.15/api/_diag/version`

---

### 方式 B：本地一键脚本（Mac 开发环境）

```bash
# 1. 构建新 jar（必做）
cd /Users/hua/Documents/myself/凌瑶
cd backend && mvn clean package -DskipTests && cd ..

# 2. 发布到 staging
./scripts/release-to-staging.sh

# 3. 验证 staging OK 后，晋升生产
./scripts/promote-to-prod.sh
# 输入 promote 确认
```

---

### 方式 C：手动 SSH（兜底）

```bash
# 1. 上传 jar
scp backend/target/lingyao-platform.jar ubuntu@118.195.197.15:~/lingyao-platform-new.jar

# 2. SSH 到 CVM 操作
ssh ubuntu@118.195.197.15
sudo cp -p /opt/lingyao/lingyao-platform.jar /opt/lingyao/lingyao-platform.jar.bak.\$(date +%Y%m%d-%H%M%S)
sudo mv ~/lingyao-platform-new.jar /opt/lingyao/lingyao-platform.jar
sudo systemctl restart lingyao-backend
sleep 30
curl http://127.0.0.1:9091/api/health
```

---

## ⚙️ 一次性环境准备（CVM 上配一次）

#### 1. 生成 release 专用 SSH key（仅 prod 机器需要）

```bash
# 以 ubuntu 用户生成专用 key（避免用主人自己的 ssh key）
sudo -u ubuntu ssh-keygen -t ed25519 -f /home/ubuntu/.ssh/release_staging_key -N ""

# 公钥加到 authorized_keys（同机自调用）
sudo -u ubuntu bash -c "cat /home/ubuntu/.ssh/release_staging_key.pub >> /home/ubuntu/.ssh/authorized_keys"
sudo -u ubuntu chmod 600 /home/ubuntu/.ssh/authorized_keys

# 测试 SSH 链路通
sudo -u ubuntu ssh -i /home/ubuntu/.ssh/release_staging_key ubuntu@127.0.0.1 echo OK
```

> **注意**：如果 staging 和 prod 在不同机器，需要把 staging 机器的 release_staging_key 公钥加到 staging 机器的 ubuntu authorized_keys。

#### 2. 配置 Webhook（飞书/钉钉，可选）

`application-private.yml` 或 `application-staging.yml` 加：

```yaml
lingyao:
  release:
    webhook-url: https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxx
    webhook-enabled: true
```

环境变量版本（推荐）：

```bash
export LINGYAO_RELEASE_WEBHOOK_URL='https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxx'
export LINGYAO_RELEASE_WEBHOOK_ENABLED=true
```

#### 3. systemd service ExecStart 校验

deploy-staging.sh / deploy-prod.sh 会在每次部署时**自动校验 + 修复** ExecStart 路径，无需手动配。

---

## 📊 状态对照表

| ReleaseStatus | 含义 | UI 颜色 |
|---|---|---|
| `RUNNING` | 部署进行中 | 🔵 蓝色 |
| `SUCCESS` | 部署成功 | 🟢 绿色 |
| `FAILED` | 部署失败（脚本退出非 0）| 🔴 红色 |
| `CANCELLED` | 被手动取消 | ⚪ 灰色 |

---

## 🔍 故障排查清单

| 现象 | 原因 | 解决方案 |
|---|---|---|
| `/api/admin/release/deploy-staging` 返 403 | 当前用户非 platformAdmin | 用 admin 账号登录，或在超管后台把账号设为 `isPlatformAdmin=true` |
| 按钮 disabled + "⏳ 部署中..." | 上次部署还没完成 | 等完成，或手动取消（CANCELLED 状态） |
| 部署卡住 > 5 分钟 | SSH 链路失败 / 脚本 hang | 看 CVM `journalctl -u lingyao-backend-staging -n 30` |
| log 字段是空 | 脚本 stdout 写入失败 | 检查 `~/lingyao-platform-new.jar` 是否成功 mv |
| Webhook 推送失败 | URL 拼错 / 网络隔离 | 测试 `curl -X POST "$WEBHOOK_URL" -H 'Content-Type: application/json' -d '{"msg_type":"text","content":{"text":"test"}}'` |

---

## 📝 版本号铁律（主人定的）

**任何改动都要 bump 版本号**：

| 改的模块 | 改的位置 |
|---|---|
| 后端代码 | `application.yml` 的 `app.version` |
| 前端代码 | (本项目前端是静态 HTML，从 `/api/version` 拉，无须单独改) |
| CHANGELOG | `CHANGELOG.md` 加一条说明 |

**发版节奏**：
1. 改代码
2. `application.yml` version +1
3. `CHANGELOG.md` 加一条
4. `git commit -m "V2.0.X: <改动说明>"`
5. `git push`
6. 部署到 staging（按上面三种方式任选）
7. 验证 staging
8. 部署到 prod
9. 验证 prod

---

## 🔗 相关资源

- 后端 release endpoint：`/api/admin/release/**`（仅 platformAdmin）
- 后端版本诊断：`/api/_diag/version`（公开）
- 部署脚本：
  - `scripts/deploy-staging.sh`（底层）
  - `scripts/deploy-prod.sh`（底层）
  - `scripts/release-to-staging.sh`（封装）
  - `scripts/promote-to-prod.sh`（封装 + 二次确认）