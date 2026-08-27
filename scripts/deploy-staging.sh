#!/bin/bash
# 凌瑶智数 · staging 9092 一键部署脚本（开发环境验证用）
# 用法：在项目根目录跑 ./scripts/deploy-staging.sh
# 流程：scp 上传 → CVM 备份旧 jar → 替换 → systemd ExecStart 路径自动校验（V2.0.3）
#       → systemctl restart lingyao-backend-staging → 等 30s Spring Boot 启动
#       → 健康检查 + 4 子产品页验证 + diag version
#
# V2.0.3 修复（部署路径错 P0-1）：
#   - staging 服务路径 = /opt/lingyao/staging/lingyao-platform.jar
#   - prod 服务路径    = /opt/lingyao/lingyao-platform.jar
#   - V2.0.2 deploy-staging.sh 写错 REMOTE_DIR=/opt/lingyao，把 jar 推到了 prod 路径，
#     staging 服务从 /opt/lingyao/staging/ 找不到 jar，部署实际不生效。
#   - V2.0.3 修：REMOTE_DIR=/opt/lingyao/staging + 自动校验 ExecStart 路径一致。
#
# 复用性：私有化部署时，jar 路径和 CVM 地址改一下即可
# 前置条件：
#   - Mac 本地默认 ssh key 已 ssh-copy-id 到 CVM ubuntu 用户
#   - 本地 backend/target/lingyao-platform.jar 已构建
#   - CVM 上 systemd 服务 lingyao-backend-staging 已 enable
#   - sudo NOPASSWD 已配（ubuntu 用户可免密 sudo）

set -e

# 自动定位项目根（脚本在 scripts/ 子目录，jar 在 backend/target/）
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao/staging"   # V2.0.3 修复: staging 服务实际路径（不是 /opt/lingyao）
JAR_NAME="lingyao-platform.jar"
LOCAL_JAR="$PROJECT_ROOT/backend/target/lingyao-platform.jar"
SERVICE_NAME="lingyao-backend-staging"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

# ── 前置校验 ──
if [ ! -f "$LOCAL_JAR" ]; then
    echo "❌ 本地 jar 不存在: $LOCAL_JAR"
    echo "   请先 build: cd backend && mvn clean package -DskipTests"
    exit 1
fi

LOCAL_SIZE=$(du -h "$LOCAL_JAR" | awk '{print $1}')
echo "📦 待发布 jar: $LOCAL_JAR ($LOCAL_SIZE)"
echo "🎯 目标: staging 9092 (${SERVICE_NAME}) → ${REMOTE_DIR}/${JAR_NAME}"
echo ""

# ── Step 1: scp 上传（-C 启用压缩）──
echo "==== [1/5] scp 上传到 CVM (-C 压缩) ===="
scp -C "$LOCAL_JAR" "$CVM:~/lingyao-platform-new.jar"

# ── Step 2: CVM 备份 + 替换 + systemd 路径迁移 + 重启 staging ──
echo ""
echo "==== [2/5] CVM 备份 + 替换 + systemd ExecStart 迁移 + 重启 staging ===="
ssh -T "$CVM" bash <<EOF
set -e
STAGING_DIR="$REMOTE_DIR"
JAR=\${STAGING_DIR}/$JAR_NAME
NEW=~/lingyao-platform-new.jar
SERVICE_FILE="$SERVICE_FILE"
TS=\$(date +%Y%m%d-%H%M%S)

echo "  -- 1) mkdir -p staging 独立目录"
sudo mkdir -p "\$STAGING_DIR"

echo "  -- 2) 备份旧 jar（如果存在）"
if [ -f "\$JAR" ]; then
    sudo cp -p "\$JAR" "\${JAR}.bak.\$TS"
    echo "     已备份: \${JAR}.bak.\$TS"
else
    echo "     无旧 jar，跳过备份"
fi

echo "  -- 3) 替换新 jar 到 staging 独立目录"
sudo mv "\$NEW" "\$JAR"
sudo chown root:root "\$JAR"

echo "  -- 4) 自动校验 systemd service ExecStart 路径（V2.0.3 修复）"
if [ ! -f "\$SERVICE_FILE" ]; then
    echo "❌ systemd service 文件不存在: \$SERVICE_FILE"
    echo "   请先在 CVM 上创建 service: sudo systemctl enable lingyao-backend-staging"
    exit 1
fi

# 备份 service 文件
sudo cp -p "\$SERVICE_FILE" "\${SERVICE_FILE}.bak.\$TS"

# 4a) 校验 jar 路径必须指向 \$STAGING_DIR/lingyao-platform.jar
EXPECTED_JAR="\$STAGING_DIR/$JAR_NAME"
if sudo grep -q "jar \$EXPECTED_JAR" "\$SERVICE_FILE"; then
    echo "     ✅ jar 路径已正确: \$EXPECTED_JAR"
else
    ACTUAL=\$(sudo grep -o -- "-jar /opt/[^ ]*lingyao-platform.jar" "\$SERVICE_FILE" | head -1)
    echo "     ⚠️  jar 路径不一致: 期望 \$EXPECTED_JAR，实际 \$ACTUAL"
    echo "     🔧 自动修复中..."
    sudo sed -i "s|-jar /opt/[^ ]*lingyao-platform.jar|-jar \$EXPECTED_JAR|g" "\$SERVICE_FILE"
    echo "     ✅ 已修正为: \$EXPECTED_JAR"
fi

# 4b) 校验 --spring.config.additional-location（如果不是则迁移）
if sudo grep -q -- "--spring.config.additional-location" "\$SERVICE_FILE"; then
    echo "     ✅ spring.config.additional-location 已配置"
elif sudo grep -q -- "--spring.config.location" "\$SERVICE_FILE"; then
    sudo sed -i 's|--spring.config.location|--spring.config.additional-location|g' "\$SERVICE_FILE"
    echo "     ✅ spring.config.location → additional-location（外部 yml 叠加，不再替换 classpath）"
fi

echo "  -- 5) systemctl daemon-reload"
sudo systemctl daemon-reload

echo "  -- 6) sudo systemctl restart ${SERVICE_NAME}（不影响 prod 9091）"
sudo systemctl restart ${SERVICE_NAME}

echo "  -- 7) 等待 Spring Boot 启动 30s..."
sleep 30
EOF

# ── Step 3: 健康检查（V2.0.3 修复：必须 ssh 到 CVM curl，因为 Mac 本地没有 9092）──
echo ""
echo "==== [3/5] 健康检查 staging 9092（CVM 内 curl）===="
HEALTH=$(ssh "$CVM" "curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:9092/api/health")

if [ "$HEALTH" != "200" ]; then
    echo ""
    echo "❌ Staging health check failed: HTTP $HEALTH"
    echo "   看 CVM staging 日志："
    ssh "$CVM" "sudo journalctl -u ${SERVICE_NAME} -n 30 --no-pager"
    exit 1
fi
echo "✅ staging health OK (HTTP $HEALTH)"

# ── Step 4: 4 子产品页验证（CVM 内 curl）──
echo ""
echo "==== [4/5] 验证 4 子产品页（CVM 内 curl）===="
ALL_OK=true
for h in geo hpd aidd por; do
    HTTP=$(ssh "$CVM" "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:9092/$h.html")
    echo "  9092 /$h.html: HTTP $HTTP"
    if [ "$HTTP" != "200" ]; then
        ALL_OK=false
    fi
done

if [ "$ALL_OK" = "false" ]; then
    echo "❌ 部分子产品页非 200"
    exit 1
fi

# ── Step 5: 版本号验证（V2.0.3：/api/_diag/version 已加 permitAll 白名单）──
echo ""
echo "==== [5/5] 版本号验证（CVM 内 curl /api/_diag/version）===="
DIAG=$(ssh "$CVM" "curl -s --max-time 10 http://127.0.0.1:9092/api/_diag/version")
echo "  /api/_diag/version: $DIAG"
if echo "$DIAG" | grep -q '"code":401'; then
    echo "⚠️  /api/_diag/version 仍被鉴权拦截（需 V2.0.3 jar 已部署）"
fi

echo ""
echo "=========================================="
echo "✅ STAGING DEPLOY SUCCESS (V2.0.3)"
echo "  - URL: http://118.195.197.15:9092/portal.html"
echo "  - 账号: admin / Staging_Admin_2026_Pg!"
echo "  - jar: ${REMOTE_DIR}/${JAR_NAME}"
echo "  - diag: $DIAG"
echo "=========================================="
