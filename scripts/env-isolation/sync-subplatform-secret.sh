#!/bin/bash
# ============================================================
# 子平台密钥同步 · Sub-platform Secret Sync
# 用途：主站 env 密钥变更后，把【同一环境】全部子平台的密钥对齐
# 目的：保证「同环境主站 == 子平台」契约成立，防止 SSO 静默断链
#
# 🚨 r7 架构验收铁律（这条 P0 就是脚本漏项造成的）：
#   本脚本初版只同步 AIinfo，漏掉棱镜 GEO。
#   后果 = 棱镜 GEO staging 一直用 prod 密钥：
#     staging token → 棱镜 staging 恒 403（同环境断链，用户进不去）
#     prod  token → 棱镜 staging 竟 200（跨环境串签，可伪造 prod 管理员）
#   「半边隔离」比完全不隔离更危险 —— 必须双子平台一起同步，
#   少同步一个就会原样复发。
#
# 用法：
#   ./sync-subplatform-secret.sh <env> [VAR_NAME] [--dry-run] [--yes]
# 例：
#   ./sync-subplatform-secret.sh staging LINGYAO_JWT_SECRET --dry-run
#   ./sync-subplatform-secret.sh staging LINGYAO_JWT_SECRET --yes
#
# 行为：
#   1. 读主站 env 的密钥值（不打印明文，只打印指纹）
#   2. 逐个子平台读现值算指纹（AIinfo + 棱镜 GEO）
#   3. 全部一致 → 直接退出 0（幂等）
#   4. 任一不一致 → 备份 → 写入 → chmod 600（备份也必须 600）
#   5. 逐个重启对应 unit，逐个做 API 复验
#
# 退出码：0=已同步或本已一致  1=失败
# ============================================================
set -uo pipefail

ENV_DIR="${ENV_DIR:-/home/ubuntu/lingyao}"
TARGET_ENV="${1:-}"
VAR_NAME="${2:-LINGYAO_JWT_SECRET}"
DRY_RUN=""; ASSUME_YES=""

for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    --yes|-y)  ASSUME_YES=1 ;;
  esac
done

if [ -z "$TARGET_ENV" ]; then
  echo "用法: $0 <env> [VAR_NAME] [--dry-run] [--yes]"
  echo "  env: prod | staging"
  echo "例: $0 staging LINGYAO_JWT_SECRET --dry-run"
  exit 1
fi

# ── 环境 → 主站 env / 全部子平台定义表 ───────────────────────────────
# 每个子平台：标签|env文件|systemd unit|健康端口|SSO 端点|启用状态端点
case "$TARGET_ENV" in
  prod)
    MAIN_ENV="${ENV_DIR}/lingyao.env"
    SUBS=(
      "AIinfo|/opt/lingyao/aiinfo-prod/runtime/.env|aiinfo-backend-prod|${AI_PROD_PORT:-18281}|/api/v1/auth/lingyao/sso-callback|/api/v1/auth/lingyao/status"
      "棱镜 GEO|${GEOM_PROD_ENV:-/opt/lingyao/geom-prod-v3/env.prod}|geom-backend-prod|${GEOM_PROD_PORT:-8094}|/api/auth/lingyao/sso-callback|/api/health"
    )
    ;;
  staging)
    MAIN_ENV="${ENV_DIR}/lingyao-staging.env"
    SUBS=(
      "AIinfo|/opt/lingyao/aiinfo-staging/runtime/.env|aiinfo-backend-staging|${AI_STG_PORT:-18280}|/api/v1/auth/lingyao/sso-callback|/api/v1/auth/lingyao/status"
      "棱镜 GEO|${GEOM_STAGING_ENV:-/opt/lingyao/geom-staging-v3/env.staging}|geom-backend-staging|${GEOM_STG_PORT:-8091}|/api/auth/lingyao/sso-callback|/api/health"
    )
    ;;
  *) echo "❌ env 只能是 prod 或 staging"; exit 1 ;;
esac

fp() { printf '%s' "$1" | sha256sum | cut -c1-16; }
getvar() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2-; }
sudo_() { if [ "$(id -u)" = "0" ]; then "$@"; else sudo -n "$@"; fi; }

if [ ! -f "$MAIN_ENV" ]; then
  echo "❌ 主站 env 不存在: $MAIN_ENV"
  exit 1
fi

MAIN_VAL=$(getvar "$MAIN_ENV" "$VAR_NAME")
if [ -z "$MAIN_VAL" ]; then
  echo "❌ 主站 env 未定义 $VAR_NAME：${MAIN_ENV}"
  exit 1
fi

MAIN_FP=$(fp "$MAIN_VAL")

echo "═══════════════════════════════════════════════════════"
echo " 子平台密钥同步 · $TARGET_ENV · $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════════════════════"
echo "主站 env : $MAIN_ENV"
echo "主站指纹 : $MAIN_FP"
echo "变量     : $VAR_NAME"
echo "待同步子平台数: ${#SUBS[@]}（AIinfo + 棱镜 GEO，缺一即复发）"

# ---------- 指纹盘点 ----------
CHANGED=0
declare -a PENDING=()
for spec in "${SUBS[@]}"; do
  IFS='|' read -r label sub_env sub_unit sub_port sso_ep status_ep <<< "$spec"

  echo ""
  echo "── $label ──"
  echo "  env  : $sub_env"
  if [ ! -f "$sub_env" ]; then
    echo "  ❌ 文件不存在，无法同步（请先修路径再重跑）"
    exit 1
  fi
  perm=$(stat -c '%a' "$sub_env" 2>/dev/null)
  echo "  perm : $perm"

  cur=$(getvar "$sub_env" "$VAR_NAME")
  if [ -n "$cur" ] && [ "$(fp "$cur")" = "$MAIN_FP" ]; then
    echo "  ✅ 指纹已一致 = $MAIN_FP（无需改动）"
    [ "$perm" = "600" ] || echo "  ⚠️  指纹虽一致但权限为 $perm，将一并收紧为 600"
    continue
  fi

  if [ -z "$cur" ]; then
    echo "  ❌ 指纹：未定义 → 将新增 $VAR_NAME"
  else
    echo "  ❌ 指纹：$(fp "$cur")  ≠ 主站 $MAIN_FP"
  fi
  PENDING+=("$label|$sub_env|$sub_unit|$sub_port|$sso_ep|$status_ep")
  CHANGED=$((CHANGED+1))
done

echo ""
if [ "$CHANGED" -eq 0 ]; then
  # 幂等：指纹全一致，仅在权限不合规时收紧
  for spec in "${SUBS[@]}"; do
    IFS='|' read -r label sub_env _rest <<< "$spec"
    perm=$(stat -c '%a' "$sub_env" 2>/dev/null)
    [ "$perm" = "600" ] || sudo_ chmod 600 "$sub_env"
  done
  echo "✅ 全部 ${#SUBS[@]} 个子平台指纹已一致，幂等退出"
  echo ""
  echo "下一步：bash check-env-isolation.sh 跑全量四象限实测"
  exit 0
fi

if [ "$DRY_RUN" = "1" ]; then
  echo ""
  echo "🔍 --dry-run：$CHANGED 个子平台需要同步，未做任何改动"
  echo "   确认无误后去掉 --dry-run 重跑"
  exit 0
fi

# ---------- 确认 ----------
echo ""
echo "⚠️  将写入 $CHANGED 个子平台 env 并重启其 unit："
for p in "${PENDING[@]}"; do IFS='|' read -r label env unit _ <<< "$p"; echo "    - $label → $env  (重启 $unit)"; done
if [ "$TARGET_ENV" = "prod" ]; then
  echo "    ⚠️  prod 密钥轮换会使 365 天 TTL 的专属链接 token 全部失效"
fi

if [ "$ASSUME_YES" != "1" ]; then
  echo ""
  read -r -p "确认同步？输入 yes 回车继续: " ANS
  [ "$ANS" = "yes" ] || { echo "已取消"; exit 0; }
fi

# ---------- 备份 + 写入 ----------
TS=$(date +%Y%m%d_%H%M%S)
echo ""
for p in "${PENDING[@]}"; do
  IFS='|' read -r label sub_env sub_unit sub_port sso_ep status_ep <<< "$p"
  BACKUP="${sub_env}.bak_${TS}"
  sudo_ cp "$sub_env" "$BACKUP"
  # r7 检查 1b 实锤：cp 产生的备份副本默认 644，含明文密钥 → 必须显式 600
  sudo_ chmod 600 "$BACKUP"
  echo "已备份($(stat -c '%a' "$BACKUP" 2>/dev/null)): $BACKUP"

  if grep -qE "^$VAR_NAME=" "$sub_env"; then
    sudo_ sed -i "s|^$VAR_NAME=.*|$VAR_NAME=$MAIN_VAL|" "$sub_env"
  else
    echo "$VAR_NAME=$MAIN_VAL" | sudo_ tee -a "$sub_env" >/dev/null
  fi
  sudo_ chmod 600 "$sub_env"

  FINAL=$(getvar "$sub_env" "$VAR_NAME")
  if [ -z "$FINAL" ] || [ "$(fp "$FINAL")" != "$MAIN_FP" ]; then
    echo "❌ $label 写入后指纹不一致，请手工检查 $sub_env"
    exit 1
  fi
  echo "✅ $label 写入成功，指纹已确认: $(fp "$FINAL")"
done

# ---------- 重启 + 逐个复验 ----------
echo ""
for p in "${PENDING[@]}"; do
  IFS='|' read -r label sub_env sub_unit sub_port sso_ep status_ep <<< "$p"
  echo "→ 重启 $sub_unit（$label）"
  sudo_ systemctl restart "$sub_unit" 2>/dev/null || sudo_ systemctl restart "$sub_unit"
  ok=0
  for i in $(seq 1 20); do
    sleep 2
    st=$(systemctl is-active "$sub_unit" 2>/dev/null)
    if [ "$st" = "active" ]; then ok=1; break; fi
  done
  st=$(systemctl is-active "$sub_unit" 2>/dev/null)
  if [ "$st" != "active" ]; then
    echo "❌ $sub_unit 重启后状态为 ${st:-未知}"
    exit 1
  fi
  echo "✅ $sub_unit = active"

  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "http://127.0.0.1:${sub_port}${status_ep}" 2>/dev/null)
  [ "$code" = "200" ] && echo "   健康: ${sub_port}${status_ep} = 200" \
                      || echo "   ⚠️  健康: ${sub_port}${status_ep} = ${code:-无响应}"
done

echo ""
echo "═══════════════════════════════════════════════════════"
echo "✅ $TARGET_ENV 子平台同步完成（${#SUBS[@]} 个子平台）"
echo "═══════════════════════════════════════════════════════"
echo "必做下一步（勿跳过）："
echo "  1. systemctl restart lingyao-backend-${TARGET_ENV}   # 主站读同一密钥"
echo "  2. bash /home/ubuntu/lingyao/scripts/check-env-isolation.sh"
echo "     ↑ 检查 3 验双子平台指纹契约，检查 6 跑两个子平台四象限实测"
echo "  3. 真实浏览器点一次：主站产品列表 → 进入 AIinfo / 棱镜 GEO"
