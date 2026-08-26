# 凌瑶智数 · CVM 生产运维与自助发布方案 V1.0

> 起草时间：2026-08-26 13:08
> 适用范围：腾讯云 CVM `lingyao-prod` (`118.195.197.15`)
> 文档定位：**一套方案 + 一步一步辅导执行**

---

## 〇、文档目的

本方案回答两个核心问题：

1. **我们现在还缺什么？怎么补？**（基础设施加固）
2. **怎么自己一个人把代码改完部署到生产？**（自助发布流水线）

前公司有运维帮忙 → 现在所有步骤必须脚本化、可重入、有回滚。

---

## 一、现状盘点

### ✅ 已有

| 组件 | 状态 | 备注 |
|---|---|---|
| JDK 21 + Spring Boot jar | ✅ | `/opt/lingyao/lingyao-platform.jar` |
| Python 3（系统自带） | ✅ | Ubuntu 22.04 默认 3.10 |
| Nginx 80 → 9091 反代 | ✅ | `/etc/nginx/sites-available/lingyao` |
| Git | ✅（Mac） | 本地仓库，待 init |
| H2 file 数据库 | ✅ | `/opt/lingyao/data/lingyao.mv.db` |
| `setsid + nohup + < /dev/null` | ✅ | 防 Web Shell stdin 死锁 |
| 进程监控端口 | ✅ | PID 96243 跑在 9091 |

### ❌ 缺失（按紧急程度）

#### 🔴 P0 · 今日必补（不补睡不踏实）

| # | 缺什么 | 后果 | 解法 |
|---|---|---|---|
| 1 | **systemd 守护服务** | CVM 重启后 Spring Boot 不会自动起来 | `/etc/systemd/system/lingyao-backend.service` |
| 2 | **logrotate** | `backend.out` 单文件无限增长，吃爆系统盘 | `/etc/logrotate.d/lingyao` |
| 3 | **H2 自动备份 cron** | 系统盘挂了 → 4 个产品/用户数据全没 | `/opt/lingyao/scripts/backup-h2.sh` |
| 4 | **`/api/_diag/health` 端点** | 没有标准健康检查，监控脚本要靠 curl login 太重 | 新增 `HealthController.java` |

#### 🟡 P1 · 本周必补（备案前能做的）

| # | 缺什么 | 解法 |
|---|---|---|
| 5 | **`monitor.sh` 监控 + 企微报警** | cron 60s 一查 + 进程死了调企微 webhook |
| 6 | **`deploy-jar.sh` 后端发布脚本** | Mac 本地一键发布，含备份+回滚+验证 |
| 7 | **`deploy-static.sh` 前端发布脚本** | Mac 本地一键发布 HTML 改动 |
| 8 | **Git 仓库初始化 + .gitignore** | 让代码版本化、脚本可追溯 |

#### 🟢 P2 · 备案通过后

| # | 缺什么 | 解法 |
|---|---|---|
| 9 | **SSL/HTTPS** | Let's Encrypt + Certbot 自动续签 |
| 10 | **DNS 解析** | `www.lydmed.com → 118.195.197.15` |
| 11 | **前端静态资源分离** | 改文案不必重打 55MB jar |

---

## 二、阶段化执行路线

```
阶段 1：基础设施加固     4 步  约 30 分钟   ← 今天必做
阶段 2：自助发布流水线   3 步  约 40 分钟   ← 今天必做
阶段 3：监控告警         1 步  约 15 分钟   ← 明天补
阶段 4：备案后优化       2 步  待 ICP 通过
```

### 阶段 1 · 基础设施加固（今日 30 分钟）

| 步骤 | 内容 | 耗时 | 位置 |
|---|---|---|---|
| **Step 1.1** | Git 仓库 init + .gitignore + 首 commit | 5 min | Mac 本地 |
| **Step 1.2** | 写 systemd 服务 + enable + 验证自启 | 10 min | CVM |
| **Step 1.3** | 写 logrotate 配置 + 强制测试切分 | 5 min | CVM |
| **Step 1.4** | 写 H2 备份脚本 + cron 注册 + 验证 | 10 min | CVM |

### 阶段 2 · 自助发布流水线（今日 40 分钟）

| 步骤 | 内容 | 耗时 | 位置 |
|---|---|---|---|
| **Step 2.1** | 写 `HealthController.java` 公开端点 | 10 min | Mac 本地 |
| **Step 2.2** | 写 `deploy-jar.sh`（含备份+回滚+验证） | 15 min | Mac 本地 |
| **Step 2.3** | 写 `deploy-static.sh`（HTML 改动专用） | 15 min | Mac 本地 |

### 阶段 3 · 监控告警（明日 15 分钟）

| 步骤 | 内容 | 耗时 | 位置 |
|---|---|---|---|
| **Step 3.1** | 写 `monitor.sh` + 企微 webhook + cron | 15 min | CVM |

### 阶段 4 · 备案后优化（待 ICP）

| 步骤 | 内容 |
|---|---|
| **Step 4.1** | SSL/HTTPS（Certbot + Nginx 443） |
| **Step 4.2** | 前端静态资源分离（Nginx 直接 serve `static/`） |

---

## 三、详细规格

### 3.1 systemd 服务文件

`/etc/systemd/system/lingyao-backend.service`

```ini
[Unit]
Description=Lingyao Intelligence Platform Backend (Spring Boot)
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/lingyao
ExecStart=/usr/bin/java -Xms512m -Xmx1536m -jar /opt/lingyao/lingyao-platform.jar \
  --server.port=9091 \
  --spring.profiles.active=private \
  --spring.config.location=/opt/lingyao/application-private.yml
Restart=always
RestartSec=5
StandardOutput=append:/opt/lingyao/logs/backend.out
StandardError=append:/opt/lingyao/logs/backend.out

[Install]
WantedBy=multi-user.target
```

启用：
```bash
systemctl daemon-reload
systemctl enable lingyao-backend   # 开机自启
systemctl start lingyao-backend    # 立刻启动
systemctl status lingyao-backend   # 看状态
```

### 3.2 logrotate 配置

`/etc/logrotate.d/lingyao`

```
/opt/lingyao/logs/*.out {
    daily
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    dateext
    dateformat -%Y%m%d
}
```

要点：`copytruncate` 让 Spring Boot 继续往原文件写（不需要重启）。

### 3.3 H2 自动备份脚本

`/opt/lingyao/scripts/backup-h2.sh`

```bash
#!/bin/bash
# 每天凌晨 3 点 cron 执行
# 备份 H2 数据库到数据盘
set -e

BACKUP_DIR=/mnt/datadisk0/backup/lingyao
SOURCE=/opt/lingyao/data/lingyao.mv.db
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE=$BACKUP_DIR/lingyao-$TIMESTAMP.mv.db

mkdir -p "$BACKUP_DIR"
# 先 checkpoint 锁文件，保证备份时数据一致
java -cp /opt/lingyao/lingyao-platform.jar \
  org.h2.tools.Shell \
  -url "jdbc:h2:file:/opt/lingyao/data/lingyao" \
  -user sa \
  -password "" \
  -sql "CHECKPOINT" || true
# 拷贝数据库文件
cp "$SOURCE" "$BACKUP_FILE"
# 保留最近 30 天
find "$BACKUP_DIR" -name "lingyao-*.mv.db" -mtime +30 -delete

echo "[$(date)] Backup OK: $BACKUP_FILE"
```

注册 cron：
```bash
crontab -e
# 加一行：
0 3 * * * /opt/lingyao/scripts/backup-h2.sh >> /opt/lingyao/logs/backup.log 2>&1
```

### 3.4 HealthController（Java 健康检查端点）

`backend/src/main/java/com/lingyao/platform/controller/HealthController.java`

```java
package com.lingyao.platform.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/_diag")
public class HealthController {
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "timestamp", System.currentTimeMillis(),
            "service", "lingyao-platform"
        );
    }
}
```

⚠️ **必须在 Spring Security 白名单里加 `/api/_diag/**`**，否则监控脚本 curl 会 401。

### 3.5 deploy-jar.sh（后端发布脚本，Mac 本地）

`/Users/hua/Documents/myself/凌瑶/scripts/deploy-jar.sh`

```bash
#!/bin/bash
# 用法：./deploy-jar.sh <本地新 jar 路径>
# 功能：scp 上传 → pkill 旧进程 → 备份 → mv 替换 → setsid 重启 → 等 25s → 验证
# 失败自动回滚
set -e

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao"
JAR_NAME="lingyao-platform.jar"

if [ -z "$1" ]; then
    echo "❌ 用法: $0 <新 jar 路径>"
    exit 1
fi

LOCAL_JAR="$1"
if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 文件不存在: $LOCAL_JAR"
    exit 1
fi

echo "==== 1. scp 上传到 CVM ===="
scp "$LOCAL_JAR" "$CVM:~/lingyao-platform-new.jar"

echo "==== 2. SSH 到 CVM 执行部署 ===="
ssh "$CVM" <<'EOF'
set -e
JAR=/opt/lingyao/lingyao-platform.jar
NEW=~/lingyao-platform-new.jar
TS=$(date +%Y%m%d-%H%M%S)

echo "-- 2.1 pkill 旧进程"
pkill -f lingyao-platform.jar || true
sleep 5

echo "-- 2.2 备份旧 jar"
cp -p "$JAR" "${JAR}.bak.${TS}"

echo "-- 2.3 替换新 jar"
mv "$NEW" "$JAR"

echo "-- 2.4 setsid 重启"
cd /opt/lingyao
setsid nohup /usr/bin/java -Xms512m -Xmx1536m -jar lingyao-platform.jar \
  --server.port=9091 --spring.profiles.active=private \
  --spring.config.location=/opt/lingyao/application-private.yml \
  < /dev/null > /opt/lingyao/logs/backend.out 2>&1 &
disown
echo "-- 启动指令已发，等 25 秒"
EOF

echo "==== 3. 等 25 秒看日志 ===="
sleep 25
ssh "$CVM" "tail -30 /opt/lingyao/logs/backend.out | grep -E 'Started LingyaoApplication|Tomcat started|ERROR|Exception' | tail -10"

echo "==== 4. curl 验证健康端点 ===="
HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9091/api/_diag/health)
if [ "$HEALTH" = "200" ]; then
    echo "✅ 部署成功！/api/_diag/health 返回 200"
else
    echo "❌ 健康检查失败 HTTP $HEALTH，正在自动回滚..."
    ssh "$CVM" <<'EOF'
pkill -f lingyao-platform.jar || true
sleep 5
ls -t /opt/lingyao/lingyao-platform.jar.bak.* | head -1 | xargs -I {} mv {} /opt/lingyao/lingyao-platform.jar
cd /opt/lingyao
setsid nohup /usr/bin/java -Xms512m -Xmx1536m -jar lingyao-platform.jar \
  --server.port=9091 --spring.profiles.active=private \
  --spring.config.location=/opt/lingyao/application-private.yml \
  < /dev/null > /opt/lingyao/logs/backend.out 2>&1 &
disown
EOF
    exit 1
fi
```

### 3.6 deploy-static.sh（前端发布脚本，Mac 本地）

`/Users/hua/Documents/myself/凌瑶/scripts/deploy-static.sh`

```bash
#!/bin/bash
# 用法：./deploy-static.sh <本地 HTML 路径> [caches]
# 功能：scp 上传 → Spring Boot 不重启自动生效（jar 里的优先级高，所以要重启）
# 加 caches 参数会强制 nginx 缓存刷新
set -e

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao/static"

if [ -z "$1" ]; then
    echo "❌ 用法: $0 <HTML 文件路径>"
    exit 1
fi

LOCAL_FILE="$1"
FILENAME=$(basename "$LOCAL_FILE")

echo "==== 1. scp 上传到 CVM ===="
scp "$LOCAL_FILE" "$CVM:$REMOTE_DIR/$FILENAME"

echo "==== 2. 验证文件已上传 ===="
ssh "$CVM" "ls -la $REMOTE_DIR/$FILENAME"

echo "==== 3. ⚠️ 提醒：jar 里的静态资源优先级更高，需要重启 Spring Boot ===="
echo "==== 重启请运行：./deploy-jar.sh /Users/hua/Documents/myself/凌瑶/backend/target/lingyao-platform.jar"
echo "==== 或先重新打包（mvn package）再 deploy-jar ===="
echo ""
echo "✅ 静态文件已上传，待重启 jar 后生效"
```

### 3.7 monitor.sh（监控告警脚本，CVM）

`/opt/lingyao/scripts/monitor.sh`

```bash
#!/bin/bash
# 每 60 秒 cron 一次
# 检查 /api/_diag/health + 进程存活 + 磁盘空间
# 任何异常调企微群机器人 webhook
set -e

HEALTH_URL="http://127.0.0.1:9091/api/_diag/health"
WEBHOOK_URL="<企微群机器人 webhook URL>"  # 主人后续在 .env.private 配
LOG=/opt/lingyao/logs/monitor.log

notify() {
    local msg="$1"
    echo "[$(date)] $msg" >> "$LOG"
    # 调企微 webhook
    if [ -n "$WEBHOOK_URL" ]; then
        curl -s -X POST "$WEBHOOK_URL" \
            -H 'Content-Type: application/json' \
            -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[凌瑶智数告警] $msg\"}}" > /dev/null || true
    fi
}

# 1. 健康检查
HTTP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$HEALTH_URL")
if [ "$HTTP" != "200" ]; then
    notify "健康检查失败 HTTP=$HTTP, 进程: $(pgrep -f lingyao-platform.jar | wc -l) 个"
    # 自动拉起
    if [ "$(pgrep -f lingyao-platform.jar | wc -l)" = "0" ]; then
        cd /opt/lingyao
        setsid nohup /usr/bin/java -Xms512m -Xmx1536m -jar lingyao-platform.jar \
          --server.port=9091 --spring.profiles.active=private \
          --spring.config.location=/opt/lingyao/application-private.yml \
          < /dev/null > /opt/lingyao/logs/backend.out 2>&1 &
        disown
        notify "已自动拉起 Spring Boot"
    fi
fi

# 2. 磁盘检查
DISK=$(df -h /opt/lingyao | tail -1 | awk '{print $5}' | tr -d '%')
if [ "$DISK" -gt 80 ]; then
    notify "磁盘使用 ${DISK}%，请清理 /opt/lingyao/logs"
fi
```

注册 cron：
```bash
crontab -e
# 加一行：
* * * * * /opt/lingyao/scripts/monitor.sh >> /opt/lingyao/logs/monitor.log 2>&1
```

---

## 四、回滚预案

### 后端回滚
```bash
ssh ubuntu@118.195.197.15 "ls -t /opt/lingyao/lingyao-platform.jar.bak.* | head -3"
# 选一个时间点的备份，回滚：
ssh ubuntu@118.195.197.15 "pkill -f lingyao-platform.jar; sleep 5; cp /opt/lingyao/lingyao-platform.jar.bak.20260826-120500 /opt/lingyao/lingyao-platform.jar; cd /opt/lingyao && setsid nohup /usr/bin/java -Xms512m -Xmx1536m -jar lingyao-platform.jar --server.port=9091 --spring.profiles.active=private --spring.config.location=/opt/lingyao/application-private.yml < /dev/null > /opt/lingyao/logs/backend.out 2>&1 & disown"
```

### 前端回滚
Spring Boot jar 里的静态资源优先级高，前端回滚必须重打 jar。建议：
- 改文案前先 git commit → 出问题 `git revert` → 重打 jar
- 或者实施阶段 4.2（静态资源分离）后，前端回滚就 scp 一份覆盖即可

---

## 五、执行检查清单

- [ ] **Step 1.1** git init + .gitignore + 首 commit
- [ ] **Step 1.2** systemd 服务 + enable + 验证自启
- [ ] **Step 1.3** logrotate 配置 + 强制测试切分
- [ ] **Step 1.4** H2 备份脚本 + cron 注册 + 验证
- [ ] **Step 2.1** HealthController + 白名单 + 重启验证
- [ ] **Step 2.2** deploy-jar.sh（Mac） + 测试发布一次
- [ ] **Step 2.3** deploy-static.sh（Mac） + 测试发布一次
- [ ] **Step 3.1** monitor.sh + 企微 webhook + cron
- [ ] **(等 ICP)** Step 4.1 SSL/HTTPS
- [ ] **(等 ICP)** Step 4.2 前端静态资源分离

---

## 六、决策记录

| 日期 | 决策 | 原因 |
|---|---|---|
| 2026-08-26 | 不上 Docker | 单机部署杀鸡用牛刀，运维成本更高 |
| 2026-08-26 | 不上 K8s | 同上 |
| 2026-08-26 | H2 不用换 PostgreSQL | 当前数据量 < 100 行，3 年内不用换 |
| 2026-08-26 | 不上 CI/CD（GitHub Actions） | 手动 scp 已足够，自动化收益低 |
| 2026-08-26 | 用 `setsid + nohup + < /dev/null` | systemd 服务化之前先用这个，Step 1.2 完成后切 systemd |

---

**主人一句话总结：今天 70 分钟搞定 8 件事，凌瑶智数从「能跑」变成「能稳」+「能自助更新」。**
