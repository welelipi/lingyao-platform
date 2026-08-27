#!/bin/bash
# 凌瑶智数 · prod 9091 一键部署脚本（生产部署）
# 用法：在项目根目录跑 ./scripts/deploy-prod.sh
# 流程：scp 上传 → CVM 备份旧 jar → 替换 → systemd ExecStart 路径自动校验（V2.0.4）
#       → sudo systemctl restart lingyao-backend → 等 30s Spring Boot 启动
#       → 健康检查 + 4 子产品页验证 + diag version
#
# V2.0.4 新增（与 deploy-staging.sh 对称）：
#   - prod 服务路径 = /opt/lingyao/lingyao-platform.jar（私有化客户部署）
#   - 自动校验 ExecStart 路径，必要时自动修正
#   - 自动迁移 --spring.config.location → additional-location（V2.0.3 修复）
#
# 前置条件：
#   - Mac 本地默认 ssh key 已 ssh-copy-id 到 CVM ubuntu 用户
#   - 本地 backend/target/lingyao-platform.jar 已构建
#   - CVM 上 systemd 服务 lingyao-backend 已 enable
#   - sudo NOPASSWD 已配（ubuntu 用户可免密 sudo）

set -e

# 自动定位项目根
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao"
JAR_NAME="lingyao-platform.jar"
LOCAL_JAR="$PROJECT_ROOT/backend/target/lingyao-platform.jar"
SERVICE_NAME="lingyao-backend"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# ── 前置校验 ──
if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 本地 jar 不存在: $LOCAL_JAR"
    echo "   请先 build: cd backend && mvn clean package -DskipTests"
    exit 1
fi

LOCAL_SIZE=$(du -h "$LOCAL_JAR" | awk '{print $1}')
echo "📦 待发布 jar: $LOCAL_JAR ($LOCAL_SIZE)"
echo "🎯 目标: prod 9091 (${SERVICE_NAME}) → ${REMOTE_DIR}/${JAR_NAME}"
echo "⚠️  警告：这是 prod 部署，会直接影响生产服务！"
echo ""

# ── Step 1: scp 上传 ──
echo "==== [1/5] scp 上传到 CVM (-C 压缩) ===="
scp -C "$LOCAL_JAR" "$CVM:~/lingyao-platform-new.jar"

# ── Step 2: CVM 备份 + 替换 + systemd 校验 + 重启 ──
echo ""
echo "==== [2/5] CVM 备份 + 替换 + systemd ExecStart 校验 + 重启 prod ===="
ssh -T "$CVM" bash <<EOF
set -e
PROD_DIR="$REMOTE_DIR"
JAR=\${PROD_DIR}/$JAR_NAME
NEW=~/lingyao-platform-new.jar
SERVICE_FILE="$SERVICE_FILE"
TS=\$(date +%Y%m%d-%H%M%S)

echo "  -- 1) 备份旧 jar"
if [ -f "\$JAR" ]; then
    sudo cp -p "\$JAR" "\${JAR}.bak.\$TS"
    echo "     已备份: \${JAR}.bak.\$TS"
else
    echo "     无旧 jar，跳过备份"
fi

echo "  -- 2) 替换新 jar 到 prod 目录"
sudo mv "\$NEW" "\$JAR"
sudo chown root:root "\$JAR"

echo "  -- 3) 自动校验 systemd service ExecStart 路径（V2.0.4 新增）"
if [ ! -f "\$SERVICE_FILE" ]; then
    echo "❌ systemd service 文件不存在: \$SERVICE_FILE"
    exit 1
fi

# 备份 service 文件
sudo cp -p "\$SERVICE_FILE" "\${SERVICE_FILE}.bak.\$TS"

# 3a) 校验 jar 路径必须指向 \$PROD_DIR/lingyao-platform.jar
EXPECTED_JAR="\$PROD_DIR/$JAR_NAME"
if sudo grep -q "jar \$EXPECTED_JAR" "\$SERVICE_FILE"; then
    echo "     ✅ jar 路径已正确: \$EXPECTED_JAR"
else
    ACTUAL=\$(sudo grep -o -- "-jar /opt/[^ ]*lingyao-platform.jar" "\$SERVICE_FILE" | head -1)
    echo "     ⚠️  jar 路径不一致: 期望 \$EXPECTED_JAR，实际 \$ACTUAL"
    sudo sed -i "s|-jar /opt/[^ ]*lingyao-platform.jar|-jar \$EXPECTED_JAR|g" "\$SERVICE_FILE"
    echo "     ✅ 已修正为: \$EXPECTED_JAR"
fi

# 3b) 校验 --spring.config.additional-location
if sudo grep -q -- "--spring.config.additional-location" "\$SERVICE_FILE"; then
    echo "     ✅ spring.config.additional-location 已配置"
elif sudo grep -q -- "--spring.config.location" "\$SERVICE_FILE"; then
    sudo sed -i 's|--spring.config.location|--spring.config.additional-location|g' "\$SERVICE_FILE"
    echo "     ✅ spring.config.location → additional-location"
fi

echo "  -- 4) systemctl daemon-reload"
sudo systemctl daemon-reload

echo "  -- 5) sudo systemctl restart ${SERVICE_NAME}"
sudo systemctl restart ${SERVICE_NAME}

echo "  -- 6) 自动清理 application-private.yml 末尾 app.version 块（V2.0.4 修复）"
PRIVATE_YML=/opt/lingyao/application-private.yml
if [ -f "\$PRIVATE_YML" ] && sudo grep -q "^app:" "\$PRIVATE_YML"; then
    sudo cp -p "\$PRIVATE_YML" "\${PRIVATE_YML}.bak.\$TS"
    sudo sed -i '/^app:$/,/^  git-commit:/d' "\$PRIVATE_YML"
    echo "     ✅ 已删除 application-private.yml 末尾 app.version 块（让 jar 内 application.yml 生效）"
    # 改完 yml 必须重启才能生效
    echo "  -- 7) sudo systemctl restart ${SERVICE_NAME}（让 yml 改动生效）"
    sudo systemctl restart ${SERVICE_NAME}
else
    echo "     ⏭  application-private.yml 末尾无 app.version 块，跳过"
fi

echo "  -- 8) 等待 Spring Boot 启动 30s..."
sleep 30
EOF

# ── Step 3: 健康检查 ──
echo ""
echo "==== [3/5] 健康检查 prod 9091 ===="
HEALTH=$(ssh "$CVM" "curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:9091/api/health")

if [ "$HEALTH" != "200" ]; then
    echo ""
    echo "❌ Prod health check failed: HTTP $HEALTH"
    echo "   看 CVM prod 日志："
    ssh "$CVM" "sudo journalctl -u ${SERVICE_NAME} -n 30 --no-pager"
    exit 1
fi
echo "✅ prod health OK (HTTP $HEALTH)"

# ── Step 4: 4 子产品页验证 ──
echo ""
echo "==== [4/5] 验证 4 子产品页 ===="
ALL_OK=true
for h in geo hpd aidd por; do
    HTTP=$(ssh "$CVM" "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:9091/$h.html")
    echo "  9091 /$h.html: HTTP $HTTP"
    if [ "$HTTP" != "200" ]; then
        ALL_OK=false
    fi
done

if [ "$ALL_OK" = "false" ]; then
    echo "❌ 部分子产品页非 200"
    exit 1
fi

# ── Step 5: 版本号验证 ──
echo ""
echo "==== [5/5] 版本号验证 ===="
DIAG=$(ssh "$CVM" "curl -s --max-time 10 http://127.0.0.1:9091/api/_diag/version")
echo "  /api/_diag/version: $DIAG"
if echo "$DIAG" | grep -q '"code":401'; then
    echo "⚠️  /api/_diag/version 仍被鉴权拦截"
fi

echo ""
echo "=========================================="
echo "✅ PROD DEPLOY SUCCESS (V2.0.4)"
echo "  - URL: http://118.195.197.15/portal.html (Nginx 80 反代到 9091)"
echo "  - 账号: admin / admin123"
echo "  - jar: ${REMOTE_DIR}/${JAR_NAME}"
echo "  - diag: $DIAG"
echo "=========================================="
