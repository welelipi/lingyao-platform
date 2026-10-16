#!/bin/bash
# ============================================================
# 版本与前端资产一致性守卫
# 目的：防止 r7 实际发生过的两类静默漂移
#   ① jar 内嵌 application.yml 版本 ≠ 源码版本（构建早于 commit）
#      → 线上跑旧代码，却以为修复已上线
#   ② website/ 与 backend/src/main/resources/static/ 双目录漂移
#      → 部署脚本发旧副本，页面缺文件/走老逻辑
#
# 用法：
#   ./check-version-consistency.sh [jar路径]        # 默认 backend/target/lingyao-platform.jar
#   ./check-version-consistency.sh --src-only        # 不检查 jar，只查双目录漂移
#
# 退出码：0=通过  1=不一致
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${1:-$ROOT/backend/target/lingyao-platform.jar}"
SRC_ONLY=0
[ "${1:-}" = "--src-only" ] && { SRC_ONLY=1; JAR=""; }

APP_YML="$ROOT/backend/src/main/resources/application.yml"
STATIC_SRC="$ROOT/backend/src/main/resources/static"
WEBSITE="$ROOT/website"

ERRORS=0
pass() { echo "  ✅ $*"; }
fail() { echo "  ❌ $*"; ERRORS=$((ERRORS+1)); }

echo "═══════════════════════════════════════════════════════"
echo " 版本与前端资产一致性守卫 · $(date '+%Y-%m-%d %H:%M:%S')"
echo "═══════════════════════════════════════════════════════"

# ---------- 检查 1：源码版本号唯一 ----------
echo ""
echo "【检查 1】源码版本号"
if [ ! -f "$APP_YML" ]; then
  fail "application.yml 不存在：$APP_YML"
else
  SRC_VER=$(grep -A3 '^app:' "$APP_YML" | grep -oE 'version:[[:space:]]*[0-9.]+' | head -1 | tr -d ' ' | cut -d: -f2)
  if [ -z "$SRC_VER" ]; then
    fail "无法从 application.yml 解析 app.version"
  else
    pass "源码 app.version = $SRC_VER"
    # 所有 HTML 的【权威版本位置】必须一致。
    # 🚨 判定口径铁律（守卫首跑即误报的教训）：
    #   绝不能用「页面里出现的第一个版本号字符串」当版本——那是代码注释里的
    #   历史变更标注（如 "<!-- V2.0.77.24：xxx -->"），与真实版本无关。
    #   首跑时 index.html 被误报成 V2.0.77.24、reset-password.html 误报 V2.0.78.19，
    #   实际 meta app-version / ?v= 均为 2.0.78.24，完全正常。
    #   权威位置只有两处：<meta name="app-version" content="V..."> 与 ?v=<version>。
    bad=0
    for h in "$STATIC_SRC"/*.html; do
      [ -f "$h" ] || continue
      hb=$(basename "$h")
      # 权威位 1：meta app-version
      mv=$(grep -oE '<meta name="app-version" content="V?[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"' "$h" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      # 权威位 2：?v= 缓存参数
      qv=$(grep -oE '\?v=[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' "$h" 2>/dev/null | head -1 | sed 's/?v=//')
      for v in "$mv" "$qv"; do
        [ -z "$v" ] && continue
        if [ "$v" != "$SRC_VER" ]; then
          fail "$hb 权威版本位为 $v ≠ 源码 $SRC_VER"
          bad=1
        fi
      done
      [ -z "$mv" ] && [ -z "$qv" ] && echo "  ⚠️  $hb 未找到权威版本位（meta/?v=），跳过版本校验"
    done
    [ $bad -eq 0 ] && pass "全部 HTML 权威版本位（meta/?v=）与源码一致"

    # console banner 版本（DEV-11 实锤漏项：portal.html:12 / company-admin.html:10
    # 曾长期停在 V2.0.78.5，meta 与 ?v= 都是对的，只有 banner 旧）
    bann=0
    for h in "$STATIC_SRC"/*.html; do
      [ -f "$h" ] || continue
      bv=$(grep -oE "console\.log\('%c [a-z-]+\.html V[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+" "$h" 2>/dev/null \
           | grep -oE 'V[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | tr -d 'V' | head -1)
      [ -z "$bv" ] && continue
      if [ "$bv" != "$SRC_VER" ]; then
        fail "$(basename "$h") console banner 版本 $bv ≠ 源码 $SRC_VER"
        bann=1
      fi
    done
    [ $bann -eq 0 ] && pass "全部 console banner 版本与源码一致"
    # index.html 的 script.js?v= 缓存破除参数也必须一致
    iv=$(grep -oE 'script\.js\?v=[0-9.]+' "$STATIC_SRC/index.html" 2>/dev/null | head -1 | sed 's/.*v=//')
    if [ -n "$iv" ]; then
      [ "$iv" = "$SRC_VER" ] && pass "index.html script.js?v=$iv 与源码一致" \
        || fail "index.html script.js?v=$iv ≠ 源码 $SRC_VER（浏览器会拿旧缓存脚本）"
    fi
  fi
fi

# ---------- 检查 2：jar 内嵌版本 ----------
if [ "$SRC_ONLY" = "0" ]; then
  echo ""
  echo "【检查 2】jar 内嵌版本"
  if [ ! -f "$JAR" ]; then
    fail "jar 不存在：$JAR（请先 mvn -o package -DskipTests）"
  elif ! command -v unzip >/dev/null 2>&1; then
    echo "  ⚠️  unzip 不可用，跳过 jar 校验"
  else
    JAR_YML=$(unzip -p "$JAR" BOOT-INF/classes/application.yml 2>/dev/null)
    JAR_VER=$(echo "$JAR_YML" | grep -A3 '^app:' | grep -oE 'version:[[:space:]]*[0-9.]+' | head -1 | tr -d ' ' | cut -d: -f2)
    if [ -z "$JAR_VER" ]; then
      fail "无法从 jar 解析 app.version"
    elif [ "$JAR_VER" != "$SRC_VER" ]; then
      fail "jar 内嵌版本 $JAR_VER ≠ 源码 $SRC_VER → 部署后线上仍是旧代码！"
      echo "      r7 实锤：本地 jar 构建时间早于 commit，构建成功但未包含修复。"
      echo "      修法：mvn -o package -DskipTests 重新构建，再部署。"
    else
      pass "jar 内嵌版本 $JAR_VER 与源码一致"
    fi
    # jar 内嵌静态页版本（同样只认权威位 meta/?v=，不看注释）
    jh=$(unzip -p "$JAR" BOOT-INF/classes/static/index.html 2>/dev/null \
         | grep -oE '<meta name="app-version" content="V?[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"' \
         | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    if [ -n "$jh" ]; then
      [ "$jh" = "$SRC_VER" ] && pass "jar 内嵌 index.html meta 版本 $jh 一致" \
        || fail "jar 内嵌 index.html meta 版本 $jh ≠ 源码 $SRC_VER"
    fi
    # 关键页面必须真的打进 jar
    for p in reset-password.html superadmin.html portal.html index.html; do
      unzip -l "$JAR" 2>/dev/null | grep -q "BOOT-INF/classes/static/$p" \
        && pass "jar 含 $p" || fail "jar 缺 $p（线上访问会 404）"
    done
  fi
fi

# ---------- 检查 3：website/ 与 jar 真源双目录漂移 ----------
echo ""
echo "【检查 3】website/ 必须与 backend static 真源一致（防僵尸副本复发）"
if [ -d "$WEBSITE/static" ]; then
  fail "website/static/ 又出现了 —— r7 PRD-1.3 僵尸副本复发！"
  echo "      真源在 website/js/ 与 backend static/，该目录无人引用且已漂移 $(( $(wc -l < website/static/js/script.js 2>/dev/null || echo 0) - $(wc -l < website/js/script.js 2>/dev/null || echo 0) )) 行"
  echo "      修法：确认无引用后 rm -rf website/static"
else
  pass "website/static/ 不存在（无僵尸副本）"
fi

drift=0
for f in "$STATIC_SRC"/*.html; do
  b=$(basename "$f")
  [ -f "$f" ] || continue
  if [ ! -f "$WEBSITE/$b" ]; then
    fail "website/$b 缺失（jar 真源存在，部署会漏发该页面）"
    drift=1
  elif ! diff -q "$f" "$WEBSITE/$b" >/dev/null 2>&1; then
    fail "website/$b 与真源内容不一致"
    drift=1
  fi
done
[ $drift -eq 0 ] && pass "website/ 与真源全部一致"

# ---------- 检查 3b：js/ 与 css/ 同样必须一致（NEW-01 病根） ----------
# 🚨 r7 研发回归 NEW-01 实锤：守卫首版只比对 *.html，
#    故意往 website/js/script.js 追加一行 → 守卫全绿通过（漏网）。
#    真实历史故障正是 script.js 副本落后 189 行、
#    GEO 两图渲染函数缺失。所以静态资源必须全目录比对，不能只比 HTML。
echo ""
echo "【检查 3b】website/js|css 与真源一致性"
sub_drift=0
for sub in js css; do
  src_sub="$STATIC_SRC/$sub"
  web_sub="$WEBSITE/$sub"
  [ -d "$src_sub" ] || continue
  if [ ! -d "$web_sub" ]; then
    fail "website/$sub/ 缺失（jar 真源存在该目录，部署会漏发静态资源）"
    sub_drift=1
    continue
  fi
  while IFS= read -r f; do
    b=$(basename "$f")
    if [ ! -f "$web_sub/$b" ]; then
      fail "website/$sub/$b 缺失（真源存在）"
      sub_drift=1
    elif ! diff -q "$f" "$web_sub/$b" >/dev/null 2>&1; then
      dl=$(diff "$f" "$web_sub/$b" 2>/dev/null | grep -c '^[<>]')
      fail "website/$sub/$b 与真源不一致（差异 $dl 行）"
      sub_drift=1
    fi
  done < <(find "$src_sub" -maxdepth 1 -type f 2>/dev/null)
done
[ $sub_drift -eq 0 ] && pass "website/js|css 与真源一致"

# jar 内的 js/css 也要与真源一致（否则线上跑的是 jar 里的旧副本）
if [ "$SRC_ONLY" = "0" ] && [ -f "$JAR" ] && command -v unzip >/dev/null 2>&1; then
  jar_sub_drift=0
  for sub in js css; do
    src_sub="$STATIC_SRC/$sub"
    [ -d "$src_sub" ] || continue
    while IFS= read -r f; do
      b=$(basename "$f")
      unzip -l "$JAR" 2>/dev/null | grep -q "BOOT-INF/classes/static/$sub/$b" || {
        fail "jar 缺 static/$sub/$b（线上静态资源 404）"; jar_sub_drift=1; continue; }
      unzip -p "$JAR" "BOOT-INF/classes/static/$sub/$b" 2>/dev/null > "/tmp/_j.$$"
      if ! diff -q "$f" "/tmp/_j.$$" >/dev/null 2>&1; then
        dl=$(diff "$f" "/tmp/_j.$$" 2>/dev/null | grep -c '^[<>]')
        fail "jar 内 static/$sub/$b 与真源不一致（差异 $dl 行）"
        jar_sub_drift=1
      fi
      rm -f "/tmp/_j.$$"
    done < <(find "$src_sub" -maxdepth 1 -type f 2>/dev/null)
  done
  [ $jar_sub_drift -eq 0 ] && pass "jar 内 js|css 与真源一致"
fi

# ---------- 汇总 ----------
echo ""
echo "═══════════════════════════════════════════════════════"
if [ "$ERRORS" -eq 0 ]; then
  echo "✅ 版本一致性校验通过"
  echo "═══════════════════════════════════════════════════════"
  exit 0
else
  echo "❌ 版本一致性校验失败：错误 $ERRORS 条"
  echo "   若要发布新 jar，先修上述不一致并重新构建；勿用旧 jar 覆盖线上。"
  echo "═══════════════════════════════════════════════════════"
  exit 1
fi
