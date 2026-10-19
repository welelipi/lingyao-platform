#!/usr/bin/env bash
# 🔴 V1.1.77 · 主站 JVM 参数与跨环境隔离自检（部署后必跑）
#
# 覆盖两类「配置层写了 ≠ 运行态生效」的隐患：
#   ① JVM 参数（Metaspace 上界是否真的生效、危险参数是否已移除）
#   ② 跨环境 env 隔离（staging 是否误读生产 env —— AK-6 的延伸）
#
# 只读：仅用 systemctl show / ps，不改任何配置。
# 退出码：0 = 通过；1 = 有问题
set -uo pipefail

FAILED=0
PROD_SVC=lingyao-backend-prod
STAGING_SVC=lingyao-backend-staging

echo "=============================================="
echo " 主站 JVM / 环境隔离自检  $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="

# ── ① JVM 参数检查 ────────────────────────────────────────────────────
check_jvm() {
  local svc="$1"
  echo
  echo "── ${svc} · JVM 参数 ──"
  if ! systemctl is-active --quiet "$svc"; then
    echo "  ✗ 服务未运行"
    FAILED=1
    return
  fi
  local cmd
  cmd=$(systemctl show "$svc" -p ExecStart --value)

  # MaxMetaspaceSize 必须存在（元空间原本无上闸，实测已用 99.15%）
  if echo "$cmd" | grep -q "MaxMetaspaceSize"; then
    echo "  ✓ MaxMetaspaceSize 已设置：$(echo "$cmd" | grep -o 'MaxMetaspaceSize=[0-9a-zA-Z]*')"
  else
    echo "  ✗ MaxMetaspaceSize 缺失 —— 元空间无上闸，可能触发无法预测的 global OOM"
    FAILED=1
  fi

  # ExitOnOutOfMemoryError 应保留（Restart=always+5s 下是正确选择）
  if echo "$cmd" | grep -q "ExitOnOutOfMemoryError"; then
    echo "  ✓ ExitOnOutOfMemoryError 已启用（OOM 即杀 + 5 秒自愈）"
  else
    echo "  ⚠ ExitOnOutOfMemoryError 未启用 —— 堆爆后会变降级服务，可能成为吃内存的僵尸进程"
    FAILED=1
  fi

  # CrashOnOutOfMemoryError 应移除（OOM 时产生 core dump，磁盘已用 28G）
  if echo "$cmd" | grep -q "CrashOnOutOfMemoryError"; then
    echo "  ✗ CrashOnOutOfMemoryError 仍在 —— OOM 会生成 core dump 撑爆磁盘"
    FAILED=1
  else
    echo "  ✓ CrashOnOutOfMemoryError 已移除"
  fi
}

check_jvm "$PROD_SVC"
check_jvm "$STAGING_SVC"

# ── ② 跨环境 env 隔离（AK-6 延伸）────────────────────────────────────
echo
echo "── 跨环境 env 隔离 ──"
for svc in "$PROD_SVC" "$STAGING_SVC"; do
  if ! systemctl is-active --quiet "$svc"; then
    continue
  fi
  envs=$(systemctl show "$svc" -p EnvironmentFiles --value)
  echo "  ${svc} EnvironmentFiles: ${envs:-（无）}"
done

if systemctl is-active --quiet "$STAGING_SVC"; then
  STAGING_ENV=$(systemctl show "$STAGING_SVC" -p EnvironmentFiles --value)
  if echo "$STAGING_ENV" | grep -q "lingyao-staging.env"; then
    echo "  ✓ staging 读的是 staging 专属 env"
    if echo "$STAGING_ENV" | grep -q "lingyao\.env" && ! echo "$STAGING_ENV" | grep -q "lingyao-staging\.env"; then
      echo "  ✗ staging 误读生产 env！"
      FAILED=1
    fi
  else
    echo "  ✗ staging 未读 lingyao-staging.env —— 可能读了生产 env"
    FAILED=1
  fi
fi

# ── ③ 资源现状 ────────────────────────────────────────────────────────
echo
echo "── 内存现状 ──"
for svc in "$PROD_SVC" "$STAGING_SVC" geom-backend-prod geom-backend-staging; do
  pid=$(systemctl show "$svc" -p MainPID --value 2>/dev/null)
  case "$pid" in
    ""|0) continue ;;
  esac
  rss=$(awk '/^VmRSS:/{print $2}' "/proc/${pid}/status" 2>/dev/null)
  hwm=$(awk '/^VmHWM:/{print $2}' "/proc/${pid}/status" 2>/dev/null)
  [ -n "${rss:-}" ] && printf "  %-26s RSS=%-8s HWM=%-8s (KB)\n" "$svc" "$rss" "${hwm:-?}"
done

echo
echo "=============================================="
if [ "$FAILED" -eq 0 ]; then
  echo " 结果：✅ 全部通过"
  echo "=============================================="
  exit 0
else
  echo " 结果：❌ 有检查项未通过"
  echo "=============================================="
  exit 1
fi
