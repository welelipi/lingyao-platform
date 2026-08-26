#!/bin/bash
# 凌瑶智数 · 后端 jar 一键发布脚本
# 用法：./deploy-jar.sh <本地新 jar 路径>
# 流程：scp 上传 → CVM 备份旧 jar → 替换 → systemctl restart → 等 25s → curl 验证 → 失败自动回滚
#
# 前置条件：
#   - Mac 本地有 ssh key 或知道 ubuntu 密码
#   - CVM 上 systemd 服务 lingyao-backend 已 enable
#   - CVM 上 /api/health 已白名单

set -e

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao"
JAR_NAME="lingyao-platform.jar"
HEALTH_URL="http://118.195.197.15/api/health"

# ── 参数校验 ──
if [ -z "$1" ]; then
    echo "❌ 用法: $0 <本地新 jar 路径>"
    echo "   示例: $0 /Users/hua/Documents/myself/凌瑶/backend/target/lingyao-platform.jar"
    exit 1
fi

LOCAL_JAR="$1"
if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 文件不存在: $LOCAL_JAR"
    exit 1
fi

LOCAL_SIZE=$(du -h "$LOCAL_JAR" | awk '{print $1}')
echo "📦 待发布 jar: $LOCAL_JAR ($LOCAL_SIZE)"
echo ""

# ── Step 1: scp 上传 ──
echo "==== [1/4] scp 上传到 CVM ===="
scp "$LOCAL_JAR" "$CVM:~/lingyao-platform-new.jar"

# ── Step 2: CVM 备份 + 替换 + 重启 ──
echo ""
echo "==== [2/4] CVM 备份 + 替换 + systemctl 重启 ===="
ssh -T "$CVM" bash <<EOF
set -e
JAR=$REMOTE_DIR/$JAR_NAME
NEW=~/lingyao-platform-new.jar
TS=\$(date +%Y%m%d-%H%M%S)

echo "  -- 备份旧 jar"
sudo cp -p "\$JAR" "\${JAR}.bak.\$TS"

echo "  -- 替换新 jar"
sudo mv "\$NEW" "\$JAR"

echo "  -- systemctl restart lingyao-backend"
sudo systemctl restart lingyao-backend
echo "  -- 完成"
EOF

# ── Step 3: 等 25 秒 + 看启动日志 ──
echo ""
echo "==== [3/4] 等 25 秒 Spring Boot 启动 ===="
sleep 25
ssh "$CVM" "tail -30 $REMOTE_DIR/logs/backend.out | grep -E 'Started LingyaoApplication|Tomcat started|ERROR|Exception' | tail -10"

# ── Step 4: curl 验证 ──
echo ""
echo "==== [4/4] curl 验证 /api/health ===="
HEALTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HEALTH_URL")

if [ "$HEALTH" = "200" ]; then
    echo ""
    echo "✅ 部署成功！"
    echo "   - jar: $LOCAL_JAR ($LOCAL_SIZE)"
    echo "   - URL: http://118.195.197.15/portal.html"
    echo "   - 账号: admin / admin123（首登强制改密）"
    echo ""
    echo "📝 提醒：浏览器记得 Ctrl+Shift+R 强制刷新（避免缓存旧 JS）"
else
    echo ""
    echo "❌ 健康检查失败 HTTP $HEALTH，触发自动回滚..."
    ssh -T "$CVM" bash <<EOF
set -e
LATEST=\$(ls -t $REMOTE_DIR/$JAR_NAME.bak.* | head -1)
echo "回滚到: \$LATEST"
sudo mv "\$LATEST" $REMOTE_DIR/$JAR_NAME
sudo systemctl restart lingyao-backend
sleep 10
echo "回滚完成"
EOF
    exit 1
fi
