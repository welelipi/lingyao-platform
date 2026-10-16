#!/bin/bash
# ============================================================
# 环境隔离闸门 · 被 deploy-staging.sh / deploy-prod.sh 末尾 source
#
# 目的（r7 SEC-002 根治第三层「防复发机制」）：
#   部署成功后必须过隔离闸门，否则本次部署判定为失败。
#   这让「prod/staging 共用配置」「跨环境路由指向」在部署环节就被拦下，
#   而不是在下一个 run 的质量环里才发现。
#
# 行为：
#   0. 自动分发 scripts/env-isolation/*.sh → CVM /home/ubuntu/lingyao/scripts/
#      （r7 架构验收 CONFIG-2 实锤：守卫脚本原先只存在于 .workbuddy/，
#        CVM 上那份是手工 scp 的「影子副本」，改本地并不会改线上 = 机制形同虚设）
#   1. 远端执行 /home/ubuntu/lingyao/scripts/check-env-isolation.sh
#   2. 退出码 0 → 放行（打印摘要）
#      退出码 1 → 阻断，退出码透传 1，部署方视为失败
#
# 铁律：不要用 `|| true` 绕过本闸门。确认为误报时，
#       应修 check-env-isolation.sh 或修配置，而不是跳过闸门。
# ============================================================

# 分发守卫脚本（幂等：内容一致则不传，避免无谓改动远端 mtime）
env_isolation_distribute() {
  local cvm="$1" ssh_key="$2"
  local src_dir
  src_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/env-isolation" 2>/dev/null && pwd)"
  [ -d "$src_dir" ] || { echo "  ⚠️  未找到 $src_dir，跳过分发"; return 0; }

  local n=0
  for f in check-env-isolation.sh gen-env-secret.sh sync-subplatform-secret.sh check-version-consistency.sh; do
    [ -f "$src_dir/$f" ] || continue
    ssh -i "$ssh_key" -o StrictHostKeyChecking=no -o BatchMode=yes "$cvm" \
      "mkdir -p /home/ubuntu/lingyao/scripts" >/dev/null 2>&1
    scp -i "$ssh_key" -o StrictHostKeyChecking=no -o BatchMode=yes -q \
      "$src_dir/$f" "$cvm:/home/ubuntu/lingyao/scripts/$f" >/dev/null 2>&1 \
      && n=$((n+1))
  done
  ssh -i "$ssh_key" -o StrictHostKeyChecking=no -o BatchMode=yes "$cvm" \
    "chmod +x /home/ubuntu/lingyao/scripts/*.sh" >/dev/null 2>&1
  echo "  已分发 $n 个守卫脚本到 CVM:/home/ubuntu/lingyao/scripts/"
}

env_isolation_gate() {
  local cvm="${1:?需要传入 CVM 地址}"
  local ssh_key="${2:?需要传入 SSH_KEY}"
  local label="${3:-部署}"

  # ---------- 闸门 0：本地 jar / 前端资产一致性 ----------
  # 必须在上传前拦：r7 实锤「本地 jar 构建早于 commit，构建成功但未含修复」，
  # 线上跑旧代码却以为修复已生效。放到上传之后就失去意义了。
  echo ""
  echo "==== [6/6] 环境隔离闸门（${label}）===="
  local vdir
  vdir="$(cd "$(dirname "${BASH_SOURCE[0]}")/env-isolation" 2>/dev/null && pwd)"
  if [ -f "$vdir/check-version-consistency.sh" ]; then
    if bash "$vdir/check-version-consistency.sh" >/tmp/_vc.out 2>&1; then
      echo "  ✅ jar / 前端资产一致性通过"
    else
      grep -E "❌|校验失败" /tmp/_vc.out | sed 's/^/  /'
      echo ""
      echo "❌ jar / 前端资产不一致——本次${label}判定为失败（防发布旧代码）"
      return 1
    fi
  fi

  env_isolation_distribute "$cvm" "$ssh_key"

  local gate_out
  gate_out=$(ssh -i "$ssh_key" -o StrictHostKeyChecking=no -o BatchMode=yes "$cvm" \
    "bash /home/ubuntu/lingyao/scripts/check-env-isolation.sh" 2>&1)
  local gate_rc=$?

  echo "$gate_out" | grep -E "✅|❌|⚠️|隔离校验|【检查" | sed 's/^/  /'

  if [ $gate_rc -ne 0 ]; then
    echo ""
    echo "❌ 环境隔离校验未通过（退出码 $gate_rc）——本次${label}判定为失败"
    echo "   配置层已偏离预期，SSO 可能跨环境串签或断链。请勿绕过本闸门。"
    echo "   排查顺序："
    echo "     1. 看上面 ❌ 条目定位是 env 文件还是 DB sub_task 路由"
    echo "     2. 换密钥走 ./gen-env-secret.sh（勿手工编辑 env）"
    echo "     3. 同步子平台走 ./sync-subplatform-secret.sh（已同时覆盖 AIinfo + 棱镜 GEO）"
    echo "     4. 误报则修 check-env-isolation.sh 后重跑"
    return $gate_rc
  fi

  echo ""
  echo "✅ 环境隔离闸门通过"
  return 0
}
