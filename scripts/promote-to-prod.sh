#!/bin/bash
# 凌瑶智数 · prod 9091 一键晋升脚本（V2.0.5 R-7 — 本地封装）
# 用法：./scripts/promote-to-prod.sh
#
# 与 deploy-prod.sh 等价，但封装了：
#   1. 二次确认提示（防误操作）
#   2. 上传前检查 staging 是否已验证
#   3. 失败回滚
#
# 前置条件：
#   - Staging 已 SUCCESS 部署
#   - CVM 上 systemd 服务 lingyao-backend 已 enable

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# ── 二次确认 ───────────────────────────
echo "==================================================="
echo "⚠️  即将晋升生产环境（118.195.197.15:9091）"
echo "⚠️  此操作将直接影响生产服务，请确认："
echo "    ✅ Staging 已通过完整测试"
echo "    ✅ jar 包已经验证"
echo "    ✅ 当前不在生产高峰期"
echo "==================================================="
echo ""
read -p "请输入 'promote' 确认晋升（任意其他输入取消）: " CONFIRM

if [ "$CONFIRM" != "promote" ]; then
    echo "❌ 已取消晋升"
    exit 0
fi

echo ""
echo "✅ 已确认，开始晋升..."
echo ""

# ── 调用统一部署脚本 ─────────────────────
bash "$SCRIPT_DIR/deploy-prod.sh"