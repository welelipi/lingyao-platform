#!/bin/bash
# 凌瑶智数 · 前端 HTML 一键发布脚本
# 用法：./deploy-static.sh <本地 HTML/JS/CSS 路径>
# 流程：scp 上传到 CVM static/ → 提示需要重新打包 jar 才能生效
#
# ⚠️ 重要限制：
#   Spring Boot jar 里的 static 资源优先级高于文件系统 static/，
#   所以单 scp 覆盖不会生效，必须重新 mvn package 后用 deploy-jar.sh 发布。

set -e

CVM="ubuntu@118.195.197.15"
REMOTE_DIR="/opt/lingyao/static"

# ── 参数校验 ──
if [ -z "$1" ]; then
    echo "❌ 用法: $0 <本地 HTML/JS/CSS 路径>"
    echo "   示例: $0 /Users/hua/Documents/myself/凌瑶/website/portal.html"
    exit 1
fi

LOCAL_FILE="$1"
if [ ! -f "$LOCAL_FILE" ]; then
    echo "❌ 文件不存在: $LOCAL_FILE"
    exit 1
fi

FILENAME=$(basename "$LOCAL_FILE")
echo "📝 待发布前端文件: $LOCAL_FILE"
echo ""

# ── Step 1: scp 上传到 CVM static/ ──
echo "==== [1/3] scp 上传到 CVM ===="
scp "$LOCAL_FILE" "$CVM:$REMOTE_DIR/$FILENAME"

echo ""
echo "==== [2/3] 验证文件已上传 ===="
ssh "$CVM" "ls -la $REMOTE_DIR/$FILENAME"

# ── Step 2: 提示后续步骤 ──
echo ""
echo "==== [3/3] ⚠️ 重要提示 ===="
echo ""
echo "Spring Boot jar 里的 static 资源优先级 > 文件系统 static/，"
echo "本次改动要真正生效，必须重新打包 jar："
echo ""
echo "  cd /Users/hua/Documents/myself/凌瑶/backend"
echo "  mvn package -DskipTests"
echo "  /Users/hua/Documents/myself/凌瑶/scripts/deploy-jar.sh \\"
echo "    /Users/hua/Documents/myself/凌瑶/backend/target/lingyao-platform.jar"
echo ""
echo "（如果只是测试一下能不能快速看到效果，直接重新打包会更快）"
