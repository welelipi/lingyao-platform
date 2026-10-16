#!/bin/bash
# ============================================================
# 环境隔离守卫 · Environment Isolation Guard
# 用途：每次部署后自动执行，不通过则阻断部署
# 目的：防止 prod/staging 共用配置再次发生（r7 SEC-002 根治）
# 退出码：0=通过  1=阻断
# ============================================================
set -uo pipefail

ENV_DIR="${ENV_DIR:-/home/ubuntu/lingyao}"
PROD_ENV="${PROD_ENV:-${ENV_DIR}/lingyao.env}"
STAGING_ENV="${STAGING_ENV:-${ENV_DIR}/lingyao-staging.env}"

ERRORS=0
WARNINGS=0

pass() { echo "  ✅ $*"; }
fail() { echo "  ❌ $*"; ERRORS=$((ERRORS+1)); }
warn() { echo "  ⚠️  $*"; WARNINGS=$((WARNINGS+1)); }

echo "═══════════════════════════════════════════════════════"
echo " 环境隔离守卫 · $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════════════════════"

# ---------- 检查 1：文件存在与权限 ----------
echo ""
echo "【检查 1】env 文件存在性与权限"
for f in "$PROD_ENV" "$STAGING_ENV"; do
  if [ -f "$f" ]; then
    perm=$(stat -c '%a' "$f")
    owner=$(stat -c '%U' "$f")
    if [ "$perm" = "600" ]; then
      pass "$f 权限 600"
    else
      fail "$f 权限应为 600，实际 $perm"
    fi
    [ "$owner" = "ubuntu" ] && pass "$f owner=ubuntu" || fail "$f owner 应为 ubuntu，实际 $owner"
  else
    fail "$f 不存在"
  fi
done

# ---------- 检查 1b：备份目录里的密钥副本权限 ----------
# r7 阶段④实锤：修复时 `cp env.staging $D/env.staging.bak` 会把含密钥的 env
# 复制成 644（原文件权限未必被 cp 继承到新路径的默认 umask 交集），
# 结果「主 env 全 600」但备份副本对其他用户可读 —— 只查主 env 会漏。
echo ""
echo "【检查 1b】备份目录中的密钥副本权限"
BACKUP_ROOT="${BACKUP_ROOT:-${ENV_DIR}/backups}"
if [ -d "$BACKUP_ROOT" ] && command -v sudo >/dev/null 2>&1; then
  leaked=0
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    p=$(stat -c '%a' "$f" 2>/dev/null)
    if [ "$p" != "600" ]; then
      fail "备份密钥副本权限过宽（perm=$p）：$f"
      leaked=$((leaked+1))
    fi
  done < <(sudo -n find "$BACKUP_ROOT" -type f 2>/dev/null | while read -r bf; do
           sudo -n grep -q 'LINGYAO_JWT_SECRET=' "$bf" 2>/dev/null && echo "$bf"
         done)
  [ "$leaked" -eq 0 ] && pass "备份目录密钥副本权限全部 600"
else
  warn "备份目录 $BACKUP_ROOT 不存在或无 sudo，跳过备份副本权限检查"
fi

# ---------- 检查 2：systemd 环境文件指向唯一性 ----------
echo ""
echo "【检查 2】systemd EnvironmentFiles 不得共用"
declare -A UNIT_ENV
for u in lingyao-backend-prod lingyao-backend-staging; do
  envf=$(systemctl show "$u" -p EnvironmentFiles --value 2>/dev/null | tr ',' '\n' | grep -oE '^/[^ ]+' | head -1)
  if [ -z "$envf" ]; then
    warn "$u 未设置 EnvironmentFiles（可能用其他方式注入）"
  else
    pass "$u → $envf"
    UNIT_ENV[$u]="$envf"
  fi
done

if [ -n "${UNIT_ENV[lingyao-backend-prod]:-}" ] && [ -n "${UNIT_ENV[lingyao-backend-staging]:-}" ]; then
  if [ "${UNIT_ENV[lingyao-backend-prod]}" = "${UNIT_ENV[lingyao-backend-staging]}" ]; then
    fail "两个 unit 共用同一 env 文件：${UNIT_ENV[lingyao-backend-prod]}（这是 r7 SEC-002 根因）"
  else
    pass "prod / staging 环境文件已分离"
  fi
fi

# ---------- 检查 3：JWT 密钥指纹唯一性 ----------
echo ""
echo "【检查 3】JWT 密钥指纹必须互不相同"
fp() { printf '%s' "$1" | sha256sum | cut -c1-16; }
getvar() { grep -E "^$2=" "$1" 2>/dev/null | head -1 | cut -d= -f2-; }

declare -a NAMES=() FPs=()
collect() {
  local label="$1" file="$2"
  [ -f "$file" ] || return 0
  local v
  v=$(getvar "$file" LINGYAO_JWT_SECRET)
  if [ -z "$v" ]; then
    warn "$label：未定义 LINGYAO_JWT_SECRET"
    return 0
  fi
  NAMES+=("$label"); FPs+=("$(fp "$v")")
  echo "    $label = $(fp "$v")"
}
collect "主站 prod        " "$PROD_ENV"
collect "主站 staging     " "$STAGING_ENV"
collect "AIinfo prod      " "/opt/lingyao/aiinfo-prod/runtime/.env"
collect "AIinfo staging   " "/opt/lingyao/aiinfo-staging/runtime/.env"
collect "棱镜 GEO prod    " "${GEOM_PROD_ENV:-/opt/lingyao/geom-prod-v3/env.prod}"
collect "棱镜 GEO staging " "${GEOM_STAGING_ENV:-/opt/lingyao/geom-staging-v3/env.staging}"

PROD_FP=""; STG_FP=""; AI_STG_FP=""; AI_PROD_FP=""; GEOM_PROD_FP=""; GEOM_STG_FP=""
for i in "${!NAMES[@]}"; do
  case "${NAMES[$i]}" in
    "主站 prod        ") PROD_FP="${FPs[$i]}" ;;
    "主站 staging     ") STG_FP="${FPs[$i]}" ;;
    "AIinfo prod      ") AI_PROD_FP="${FPs[$i]}" ;;
    "AIinfo staging   ") AI_STG_FP="${FPs[$i]}" ;;
    "棱镜 GEO prod    ") GEOM_PROD_FP="${FPs[$i]}" ;;
    "棱镜 GEO staging ") GEOM_STG_FP="${FPs[$i]}" ;;
  esac
done

if [ -n "$PROD_FP" ] && [ -n "$STG_FP" ]; then
  if [ "$PROD_FP" = "$STG_FP" ]; then
    fail "主站 prod 与 staging 密钥相同（$PROD_FP）—— staging 可伪造 prod 管理员 JWT"
  else
    pass "主站 prod / staging 密钥已隔离"
  fi
fi

# 关键契约：同一环境内 主站 == 子平台，否则 SSO 静默断链
# （按名字取值，不能用固定下标 —— 文件缺失时数组会错位）
# r7 阶段④实锤：漏掉棱镜 GEO staging，staging token 到棱镜恒 403、
# prod token 到棱镜 staging 竟 200 —— 隔离「半边」比不隔离更危险。
check_same_env() {
  local env_label="$1" main_fp="$2" sub_fp="$3" sub_label="$4"
  if [ -z "$main_fp" ] || [ -z "$sub_fp" ]; then
    warn "$env_label：$sub_label 密钥不可读，跳过一致性校验"
    return 0
  fi
  if [ "$main_fp" != "$sub_fp" ]; then
    fail "$env_label 主站（$main_fp）≠ $sub_label（$sub_fp）→ SSO 验签必失败"
  else
    pass "$env_label 主站 == $sub_label（SSO 契约成立）"
  fi
}
check_same_env "prod   " "$PROD_FP" "$AI_PROD_FP"  "AIinfo prod"
check_same_env "prod   " "$PROD_FP" "$GEOM_PROD_FP" "棱镜 GEO prod"
check_same_env "staging" "$STG_FP" "$AI_STG_FP"  "AIinfo staging"
check_same_env "staging" "$STG_FP" "$GEOM_STG_FP" "棱镜 GEO staging"

# 跨环境反向：子平台 prod 与 staging 不得共用
if [ -n "$AI_PROD_FP" ] && [ -n "$AI_STG_FP" ] && [ "$AI_PROD_FP" = "$AI_STG_FP" ]; then
  fail "AIinfo prod 与 staging 密钥相同（$AI_PROD_FP）"
fi
if [ -n "$GEOM_PROD_FP" ] && [ -n "$GEOM_STG_FP" ] && [ "$GEOM_PROD_FP" = "$GEOM_STG_FP" ]; then
  fail "棱镜 GEO prod 与 staging 密钥相同（$GEOM_PROD_FP）—— staging 可伪造 prod 管理员身份"
fi

# ---------- 检查 4：env 内子任务 URL 环境匹配 ----------
echo ""
echo "【检查 4】env 内子任务 URL 必须与环境匹配"
check_urls() {
  local label="$1" file="$2" expect_staging="$3"
  [ -f "$file" ] || return 0
  local geo aiinfo
  geo=$(getvar "$file" LINGYAO_SUBTASK_GEO_BASE_URL)
  aiinfo=$(getvar "$file" LINGYAO_SUBTASK_AIINFO_HEALTH_URL)
  if [ -z "$geo" ]; then
    warn "$label GEO URL 未定义（将 fallback 到 sub_task.base_url，见检查 5）"
  elif [ "$expect_staging" = "yes" ]; then
    echo "$geo" | grep -q "geo-staging" && pass "$label GEO 指向 staging" || fail "$label GEO URL 应含 geo-staging，实为：${geo}"
  else
    echo "$geo" | grep -q "geo-staging" && fail "$label 竟指向 geo-staging（prod 应用 staging 子平台）" || pass "$label GEO 指向生产"
    # 生产 URL 必须是 https（http 明文会把 platform_token 暴露在重定向链上）
    case "$geo" in
      http://*) fail "$label GEO URL 使用明文 http（token 泄漏风险）：${geo}" ;;
      https://*) pass "$label GEO URL 为 https" ;;
    esac
  fi
  # AIinfo health-url：18280=staging / 18281=prod
  if [ "$expect_staging" = "yes" ]; then
    echo "$aiinfo" | grep -q "18280" && pass "$label AIinfo 指向 staging(18280)" || fail "$label AIinfo 应为 18280，实为：${aiinfo:-未设置}"
  else
    echo "$aiinfo" | grep -q "18280" && fail "$label AIinfo 竟指向 staging(18280)" || pass "$label AIinfo 指向生产(18281)"
  fi
}
check_urls "主站 prod   " "$PROD_ENV" "no"
check_urls "主站 staging" "$STAGING_ENV" "yes"

# ---------- 检查 5：DB sub_task 路由环境匹配 ----------
# r7 实锤教训：enter() 的 URL 解析顺序是 lingyao.subtask.routes.<code>.base-url
# 优先、sub_task.base_url 兜底（SubTaskController:232-238）。env 未定义 GEO 路由时
# 实际生效的就是 DB 值 —— 只查 env 的守卫会漏掉 DB 里的跨环境指向。
echo ""
echo "【检查 5】DB sub_task 路由必须与环境匹配"
if ! command -v psql >/dev/null 2>&1; then
  warn "psql 不可用，跳过 DB 路由检查"
else
  check_db_routes() {
    local label="$1" psql_cmd="$2" dbname="$3" expect_staging="$4"
    local rows
    rows=$(eval "${psql_cmd} -t -A -F'|' -c \"SELECT p.code, s.base_url, s.health_url FROM sub_task s JOIN product p ON p.id=s.product_id WHERE p.code IN ('GEO','AIINFO');\"" 2>/dev/null)
    if [ -z "$rows" ]; then
      warn "$label 读取 sub_task 失败或无 GEO/AIINFO 行"
      return 0
    fi
    while IFS='|' read -r code base health; do
      [ -z "$code" ] && continue
      # base_url 的 staging 标记：URL 里含 -staging
      local has_staging=no
      echo "$base" | grep -q -- "-staging" && has_staging=yes
      if [ "$expect_staging" = "yes" ]; then
        [ "$has_staging" = "yes" ] && pass "$label DB $code base_url 指向 staging" \
          || fail "$label DB $code base_url 应指向 staging，实为：${base:-空}"
      else
        [ "$has_staging" = "yes" ] && fail "$label DB $code base_url 竟指向 staging：${base}" \
          || pass "$label DB $code base_url 指向生产"
        case "$base" in
          http://*) fail "$label DB $code base_url 明文 http：${base}" ;;
        esac
      fi
      # health_url 端口核对：18280=staging / 18281=prod / 8091=staging GEO / 8094=prod GEO
      case "$code" in
        AIINFO)
          if [ "$expect_staging" = "yes" ]; then
            echo "$health" | grep -q 18280 && pass "$label DB $code health 18280(staging)" || fail "$label DB $code health 应为 18280，实为：${health:-空}"
          else
            echo "$health" | grep -q 18281 && pass "$label DB $code health 18281(prod)" || fail "$label DB $code health 应为 18281，实为：${health:-空}"
          fi ;;
        GEO)
          if [ "$expect_staging" = "yes" ]; then
            echo "$health" | grep -q 8091 && pass "$label DB $code health 8091(staging)" || fail "$label DB $code health 应为 8091，实为：${health:-空}"
          else
            echo "$health" | grep -q 8094 && pass "$label DB $code health 8094(prod)" || fail "$label DB $code health 应为 8094，实为：${health:-空}"
          fi ;;
      esac
    done <<< "$rows"
  }
  # 生产库在宿主机 5434（postgres superuser）；staging 库在 docker 容器内
  PROD_PSQL="sudo -n -u postgres psql -p 5434 -d lingyao"
  STG_PSQL="sudo -n docker exec lingyao-postgres psql -U lingyao_app -d lingyao_staging"
  check_db_routes "主站 prod   " "$PROD_PSQL" lingyao no
  check_db_routes "主站 staging" "$STG_PSQL" lingyao_staging yes
fi

# ---------- 检查 6：跨环境验签隔离（四象限实测） ----------
# 指纹比对只能证明「密钥不同」，不能证明「链路真的断开」。
# r7 铁律：修复有效性要两级证明——配置层 + 真实全量流程。
echo ""
echo "【检查 6】跨环境 JWT 验签隔离（四象限实测）"
AI_STG_PORT="${AI_STG_PORT:-18280}"
AI_PROD_PORT="${AI_PROD_PORT:-18281}"
SSO_EP="/api/v1/auth/lingyao/sso-callback"

if [ -n "$PROD_FP" ] && [ -n "$STG_FP" ]; then
  # 用各环境真实密钥签一个只用于探测的 token（aud=lingyao-sso，uid=-1 不可用身份）
  mktok() {
    python3 - "$1" <<'PYEOF' 2>/dev/null
import sys, json, time, hmac, hashlib, base64
secret = ""
for ln in open(sys.argv[1]):
    if ln.startswith("LINGYAO_JWT_SECRET="):
        secret = ln.split("=", 1)[1].strip()
def b64(b): return base64.urlsafe_b64encode(b).rstrip(b"=")
def enc(o): return b64(json.dumps(o, separators=(",", ":")).encode())
now = int(time.time())
si = enc({"alg": "HS512"}) + b"." + enc({
    "uid": -1, "username": "__probe__", "cid": None, "ccode": None, "adm": False,
    "tenant": {"id": None, "code": None, "name": "probe"},
    "identity": {"user_id": "-1"}, "grants": [],
    "iss": "lingyao-platform", "aud": "lingyao-sso",
    "iat": now, "exp": now + 120})
print(si.decode() + "." + b64(hmac.new(secret.encode(), si, hashlib.sha512).digest()).decode())
PYEOF
  }
  probe() {
    # $1=token  $2=port  $3=endpoint  →  输出 "<http_code> <body前200字节>"
    # 返回响应体是必须的：见 is_verify_rejected 注释——403 在两个子平台里
    # 既可能是「验签失败」也可能是「验签通过但业务拒绝」，光看状态码会误判。
    curl -s -w '\n__CODE__%{http_code}' --max-time 8 \
      -X POST "http://127.0.0.1:${2}${3}" -H 'Content-Type: application/json' \
      -d "{\"platform_token\":\"$1\"}" 2>/dev/null
  }

  # 判定「这次 403 到底是不是验签失败」
  # 🚨 r7 架构验收实锤的判定盲区：
  #   AIinfo  : lingyao_sso.py:90/94/100  过期/audience错/签名错 → 403
  #             lingyao_sso.py:137/151      无用户/租户不符    → 403（同样码！）
  #   棱镜 GEO : lingyao_sso.py:55/57/59   过期/audience错/签名错 → 403
  #             lingyao_sso.py:261/271/279  无用户/租户不符/绑定冲突 → 403（同样码！）
  #   结论：状态码无法区分「隔离生效」与「验签通过但用户不存在」。
  #   改为按 detail 文案识别验签失败的 4 类特征，命中任一才判「隔离生效」；
  #   都不命中则视为可疑（可能验签被绕过），按 fail 处理而不是放过。
  is_verify_rejected() {
    local body="$1"
    echo "$body" | grep -qE 'token 无效|凭证无效|已过期|过期|audience'
  }
  PTOK=$(mktok "$PROD_ENV")
  STOK=$(mktok "$STAGING_ENV")

  if [ -z "$PTOK" ] || [ -z "$STOK" ]; then
    warn "探测 token 签发失败（缺 python3 或密钥不可读），跳过四象限实测"
  else
    # r7 阶段④实锤：只测 AIinfo 会漏掉棱镜 GEO 的「半边隔离」故障
    # （staging token 到棱镜恒 403、prod token 到棱镜 staging 竟 200）
    # 故两个子平台各跑一遍完整四象限。
    check_quad() {
      local sub_label="$1" stg_port="$2" prd_port="$3" ep="$4"
      local r_ss r_ps c1_code c1_body c2_code c2_body
      r_ss=$(probe "$STOK" "$stg_port" "$ep");  r_ss_code="${r_ss##*__CODE__}"
      r_ps=$(probe "$PTOK" "$prd_port" "$ep");  r_ps_code="${r_ps##*__CODE__}"

      [ "$r_ss_code" != "000" ] && [ -n "$r_ss_code" ] \
        && pass "$sub_label staging token → ${sub_label} staging 服务可达（HTTP $r_ss_code）" \
        || fail "$sub_label staging token → ${sub_label} staging 无响应（端口 $stg_port 不通）"
      [ "$r_ps_code" != "000" ] && [ -n "$r_ps_code" ] \
        && pass "$sub_label prod token → ${sub_label} prod 服务可达（HTTP $r_ps_code）" \
        || fail "$sub_label prod token → ${sub_label} prod 无响应（端口 $prd_port 不通）"

      # 跨环境 1：prod token → 子平台 staging（这是本轮 P0 的原始故障形态）
      c1=$(probe "$PTOK" "$stg_port" "$ep")
      c1_code="${c1##*__CODE__}"; c1_body="${c1%__CODE__*}"
      # 跨环境 2：staging token → 子平台 prod
      c2=$(probe "$STOK" "$prd_port" "$ep")
      c2_code="${c2##*__CODE__}"; c2_body="${c2%__CODE__*}"

      judge_cross() {
        local dir="$1" code="$2" body="$3" tgt_label="$4" tgt_port="$5"
        if [ "$code" = "000" ] || [ -z "$code" ]; then
          warn "$sub_label $dir → ${tgt_label} 无响应（端口 $tgt_port 不通），无法验证隔离"
        elif [ "$code" = "200" ]; then
          fail "$sub_label $dir 在 ${tgt_label} 返回 200（验签被绕过！跨环境隔离完全失效）"
        elif is_verify_rejected "$body"; then
          pass "$sub_label $dir → ${tgt_label} 被拒（HTTP $code，验签失败文案命中，隔离生效）"
        else
          fail "$sub_label $dir → ${tgt_label} 返回 HTTP $code 但响应体非验签失败特征：${body:0:160}"
        fi
      }
      judge_cross "prod token"   "$c1_code" "$c1_body" "${sub_label} staging" "$stg_port"
      judge_cross "staging token" "$c2_code" "$c2_body" "${sub_label} prod"    "$prd_port"
    }
    check_quad "AIinfo"   "$AI_STG_PORT" "$AI_PROD_PORT" "/api/v1/auth/lingyao/sso-callback"
    check_quad "棱镜 GEO" "${GEOM_STG_PORT:-8091}" "${GEOM_PROD_PORT:-8094}" "/api/auth/lingyao/sso-callback"
  fi
fi

# ---------- 检查 7：服务健康 ----------
# ⚠️ 两环境健康路径不同：prod 无 context-path，staging 有 /staging 前缀
#    （AK-4 铁律：路径前缀差异 ≠ 服务故障，验健康前先确认该环境约定）
echo ""
echo "【检查 7】服务健康"
check_health() {
  local label="$1" port="$2" path="$3"
  local code v
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:${port}${path}" 2>/dev/null)
  if [ "$code" = "200" ]; then
    v=$(curl -s --max-time 5 "http://127.0.0.1:${port}${path}" 2>/dev/null | grep -oE '"version":"[^"]*"' | head -1)
    pass "${label} 端口 ${port}${path} 健康 ${v}"
  else
    fail "${label} 端口 ${port}${path} 返回 ${code}（预期 200）"
  fi
}
check_health "主站 prod   " 9091 "/api/health"
check_health "主站 staging" 9092 "/staging/api/health"

# AIinfo：/api/health 存在
for p in 18280 18281; do
  if curl -s --max-time 5 -o /dev/null "http://127.0.0.1:${p}/api/health" 2>/dev/null; then
    pass "AIinfo 端口 $p 健康"
  else
    warn "AIinfo 端口 $p 未响应（若为 on-demand 可忽略）"
  fi
done

# 棱镜 GEO：两环境健康路径都是 /api/health（无 context-path 前缀）
# ⚠️ r7 阶段④实锤：棱镜曾被重启却因「没纳入检查 7」而全绿通过 ——
#    子平台健康必须与主站同级纳入，否则闸门是半盲的。
check_health "棱镜 GEO prod   " "${GEOM_PROD_PORT:-8094}" "/api/health"
check_health "棱镜 GEO staging" "${GEOM_STG_PORT:-8091}" "/api/health"

# systemd 单元实际状态（inactive/degraded 的服务不能只看 HTTP 探测）
for u in lingyao-backend-prod lingyao-backend-staging aiinfo-backend-prod aiinfo-backend-staging geom-backend-prod geom-backend-staging; do
  st=$(systemctl is-active "$u" 2>/dev/null)
  if [ "$st" = "active" ]; then
    pass "systemd $u = active"
  elif [ "$st" = "inactive" ]; then
    warn "systemd $u = inactive（若为 on-demand 可忽略，如 bge-embeddings）"
  else
    fail "systemd $u = ${st:-未知}（预期 active）"
  fi
done

# ---------- 汇总 ----------
echo ""
echo "═══════════════════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ]; then
  echo "✅ 隔离校验通过（警告 $WARNINGS 条）"
  echo "═══════════════════════════════════════════════════════"
  exit 0
else
  echo "❌ 隔离校验失败：错误 $ERRORS 条 / 警告 $WARNINGS 条"
  echo "   已阻断。若确认误报，请修正后重跑；勿绕过本守卫。"
  echo "═══════════════════════════════════════════════════════"
  exit 1
fi
