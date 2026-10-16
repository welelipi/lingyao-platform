#!/bin/bash
# ============================================================
# 环境密钥生成入口 · Environment Secret Generator
# 用途：换密钥的唯一正规入口，禁止手工编辑 env 文件
# 目的：防止「手工编辑 → 复制到另一个文件 → 共用」复发（r7 SEC-002 根治）
#
# 用法：
#   ./gen-env-secret.sh <env> <VAR_NAME> [--show]
# 例：
#   ./gen-env-secret.sh staging LINGYAO_JWT_SECRET      # 生成并写入 staging
#   ./gen-env-secret.sh prod    LINGYAO_JWT_SECRET      # 生成并写入 prod
#   ./gen-env-secret.sh staging LINGYAO_JWT_SECRET --show  # 只显示指纹不写入
#
# 特性：
#   - openssl rand -base64 48 高强度随机
#   - 写入前自动备份（时间戳）
#   - 权限强制 600
#   - 写入后校验指纹唯一性（与其他 env 比对，冲突即报警）
# ============================================================
set -uo pipefail

ENV_DIR="${ENV_DIR:-/home/ubuntu/lingyao}"
TARGET_ENV="${1:-}"
VAR_NAME="${2:-}"
SHOW_ONLY="${3:-}"

if [ -z "$TARGET_ENV" ] || [ -z "$VAR_NAME" ]; then
  echo "用法: $0 <env> <VAR_NAME> [--show]"
  echo "  env: prod | staging"
  echo "例: $0 staging LINGYAO_JWT_SECRET"
  exit 1
fi

case "$TARGET_ENV" in
  prod)    ENV_FILE="${ENV_DIR}/lingyao.env" ;;
  staging) ENV_FILE="${ENV_DIR}/lingyao-staging.env" ;;
  *) echo "❌ env 只能是 prod 或 staging"; exit 1 ;;
esac

getvar() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2-; }
fp() { printf '%s' "$1" | sha256sum | cut -c1-16; }

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ $ENV_FILE 不存在"
  exit 1
fi

OLD=$(getvar "$ENV_FILE" "$VAR_NAME")
echo "文件: $ENV_FILE"
echo "变量: $VAR_NAME"
if [ -n "$OLD" ]; then
  echo "当前指纹: $(fp "$OLD")"
else
  echo "当前: 未定义（将新增）"
fi

if [ "$SHOW_ONLY" = "--show" ]; then
  echo "（--show 模式，未写入）"
  exit 0
fi

# ---------- 冲突预检 ----------
NEW=$(openssl rand -base64 48)
NEW_FP=$(fp "$NEW")
echo ""
echo "新指纹: $NEW_FP"

OTHER_ENV="${ENV_DIR}/lingyao-staging.env"
[ "$TARGET_ENV" = "staging" ] && OTHER_ENV="${ENV_DIR}/lingyao.env"

if [ -f "$OTHER_ENV" ]; then
  OTHER_VAL=$(getvar "$OTHER_ENV" "$VAR_NAME")
  if [ -n "$OTHER_VAL" ]; then
    echo "另一环境指纹: $(fp "$OTHER_VAL")"
    [ "$(fp "$OTHER_VAL")" = "$NEW_FP" ] && { echo "❌ 冲突：新密钥与另一环境相同，拒绝写入"; exit 1; }
    echo "✅ 与另一环境无冲突"
  fi
fi

# 子平台联动提示
# 🚨 r7 架构验收实锤：初版这里只列 AIinfo，而实际有 2 个子平台共享本密钥。
#    运维照着提示只同步 AIinfo → 棱镜 GEO 掉队 → 「半边隔离」故障复发。
#    提示漏项 = 机制漏项，必须一次列全。
echo ""
if [ "$VAR_NAME" = "LINGYAO_JWT_SECRET" ]; then
  echo "⚠️  LINGYAO_JWT_SECRET 被【2 个子平台】共享，必须全部同步，否则 SSO 静默断链："
  echo "    （契约：同环境主站 == 全部子平台；跨环境必须全部不同）"
else
  echo "⚠️  若此变量需要子平台共享，请手动同步："
fi
if [ "$TARGET_ENV" = "staging" ]; then
  echo ""
  echo "    staging 需同步的子平台 env："
  echo "      1. AIinfo    /opt/lingyao/aiinfo-staging/runtime/.env   (aiinfo-backend-staging, :18280)"
  echo "      2. 棱镜 GEO  /opt/lingyao/geom-staging-v3/env.staging  (geom-backend-staging, :8091)"
  echo "    → ./sync-subplatform-secret.sh staging LINGYAO_JWT_SECRET"
  echo "      ↑ 脚本已同时处理这 2 个子平台（备份/写入/chmod 600/重启/复验），勿手工单改其一"
else
  echo ""
  echo "    prod 需同步的子平台 env："
  echo "      1. AIinfo    /opt/lingyao/aiinfo-prod/runtime/.env      (aiinfo-backend-prod, :18281)"
  echo "      2. 棱镜 GEO  /opt/lingyao/geom-prod-v3/env.prod        (geom-backend-prod, :8094)"
  echo "    → ./sync-subplatform-secret.sh prod LINGYAO_JWT_SECRET"
  echo "      ↑ 脚本已同时处理这 2 个子平台（备份/写入/chmod 600/重启/复验），勿手工单改其一"
  echo "    ⚠️  改 prod 会使 365 天 TTL 的专属链接 token 全部失效"
fi

# ---------- 确认 ----------
echo ""
read -r -p "确认写入？输入 yes 回车继续: " ANS
[ "$ANS" = "yes" ] || { echo "已取消"; exit 0; }

# ---------- 备份 + 写入 ----------
BACKUP="${ENV_FILE}.bak_$(date +%Y%m%d_%H%M%S)"
sudo cp "$ENV_FILE" "$BACKUP"
echo "已备份: $BACKUP"

if grep -qE "^$VAR_NAME=" "$ENV_FILE"; then
  sudo sed -i "s|^$VAR_NAME=.*|$VAR_NAME=$NEW|" "$ENV_FILE"
else
  echo "$VAR_NAME=$NEW" | sudo tee -a "$ENV_FILE" >/dev/null
fi
sudo chmod 600 "$ENV_FILE"

# ---------- 写后校验 ----------
FINAL=$(getvar "$ENV_FILE" "$VAR_NAME")
if [ "$(fp "$FINAL")" = "$NEW_FP" ]; then
  echo "✅ 写入成功，指纹已确认: $(fp "$FINAL")"
else
  echo "❌ 写入后指纹不一致，请检查文件"
  exit 1
fi

echo ""
echo "下一步："
echo "  1. systemctl daemon-reload"
echo "  2. 先重启子平台，再重启主站"
echo "  3. bash check-env-isolation.sh 验证"
