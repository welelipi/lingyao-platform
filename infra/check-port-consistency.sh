#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════
# 凌瑶智数 · 端口一致性校验脚本（V1.0 · 2026-09-13）
#
# 用途：CI / pre-commit 检查端口散落，避免「改一处漏改十处」
#
# 关联文档：
# - infra/port-allocation.md          端口分配单一真源表
# - infra/port-change-checklist.md    改端口 SOP
# - infra/port-config-env-template.md 端口环境变量化模板
#
# 用法：
#   ./infra/check-port-consistency.sh                # 全仓扫描
#   ./infra/check-port-consistency.sh --diff         # 跟 port-allocation.md 对比
#   ./infra/check-port-consistency.sh --strict       # 严格模式（命中即 exit 1）
#
# 返回码：
#   0 = 一致（无散落或与真源表一致）
#   1 = 不一致（散落命中）
#   2 = 工具错误
# ══════════════════════════════════════════════════════════════════

set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLOCATION_FILE="$PROJECT_ROOT/infra/port-allocation.md"
EXIT_CODE=0

# ── 配色 ──
RED='\033[0;31m'
YELLOW='\033[0;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ── 参数 ──
STRICT=false
DIFF_MODE=false
for arg in "$@"; do
  case $arg in
    --strict) STRICT=true ;;
    --diff)   DIFF_MODE=true ;;
    -h|--help)
      head -25 "$0" | tail -22
      exit 0
      ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

echo -e "${BLUE}═══════════════════════════════════════════════${NC}"
echo -e "${BLUE}  凌瑶智数 · 端口一致性校验 V1.0${NC}"
echo -e "${BLUE}  项目根: $PROJECT_ROOT${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════${NC}"
echo

# ── 1. 扫描硬编码端口 ──
echo -e "${YELLOW}[1/3] 扫描硬编码端口（grep 全仓）${NC}"

# 主站 frontend（website/）
# V2.0.77.25：8765 退役（前端由 9091 jar 内置静态资源服务），从 regex 删除 :8765
echo
echo -e "${BLUE}▶ 主站 website/ ${NC}"
WEBSITE_HITS=$(grep -rnE "(:9091|:9092|:8090|:8091|:5180|:5181)" \
    --include="*.js" --include="*.html" --include="*.vue" \
    "$PROJECT_ROOT/website" 2>/dev/null \
    | grep -v "node_modules\|dist/" \
    | grep -vE "(//.*[: ]|\*.*[: ])" \
    || true)

if [ -n "$WEBSITE_HITS" ]; then
  echo "$WEBSITE_HITS" | head -30
  HIT_COUNT=$(echo "$WEBSITE_HITS" | wc -l | tr -d ' ')
  echo -e "  ${RED}→ 命中 $HIT_COUNT 处${NC}"
  EXIT_CODE=1
else
  echo -e "  ${GREEN}→ 无硬编码端口 ✓${NC}"
fi

# 主站 backend（backend/）
echo
echo -e "${BLUE}▶ 主站 backend/ ${NC}"
BACKEND_HITS=$(grep -rnE "(9091|9092|8765|8090|5180|5181|5182|3100|13000|8280|8281|8100|18080|3190)" \
    --include="*.java" --include="*.yml" --include="*.yaml" --include="*.properties" \
    "$PROJECT_ROOT/backend/src" 2>/dev/null \
    | grep -v "target/" \
    | grep -vE "(//.*[: ]|\\*.*[: ])" \
    || true)

if [ -n "$BACKEND_HITS" ]; then
  # 过滤掉 application*.yml 中的合法端口配置（这是单一真源）
  BACKEND_HITS_FILTERED=$(echo "$BACKEND_HITS" | grep -vE "application(-[a-z]+)?\.yml:" | head -30)
  if [ -n "$BACKEND_HITS_FILTERED" ]; then
    echo "$BACKEND_HITS_FILTERED" | head -30
    HIT_COUNT=$(echo "$BACKEND_HITS_FILTERED" | wc -l | tr -d ' ')
    echo -e "  ${RED}→ 命中 $HIT_COUNT 处（排除 application*.yml 真源）${NC}"
    EXIT_CODE=1
  else
    echo -e "  ${GREEN}→ 仅 application*.yml 含端口配置（合法）✓${NC}"
  fi
else
  echo -e "  ${GREEN}→ 无硬编码端口 ✓${NC}"
fi

# GEOM frontend（geom/geo-frontend/）
echo
echo -e "${BLUE}▶ GEOM frontend ${NC}"
GEOM_HITS=$(grep -rnE "(:8090|:8091|:8092|:5180|:5181|:5182)" \
    --include="*.js" --include="*.html" --include="*.vue" --include="*.ts" \
    "$PROJECT_ROOT/geom/geo-frontend/src" 2>/dev/null \
    | grep -v "node_modules\|dist/" \
    || true)

if [ -n "$GEOM_HITS" ]; then
  echo "$GEOM_HITS" | head -20
  HIT_COUNT=$(echo "$GEOM_HITS" | wc -l | tr -d ' ')
  echo -e "  ${YELLOW}→ 命中 $HIT_COUNT 处（GEOM frontend 常见，建议改 .env）${NC}"
  # GEOM frontend 是 P2（治理性），不阻断
else
  echo -e "  ${GREEN}→ 无硬编码端口 ✓${NC}"
fi

# ── 2. 扫描 CORS 硬编码 ──
echo
echo -e "${YELLOW}[2/3] 扫描 CORS 硬编码${NC}"
# V1.0.1：过滤 Java 单行/块注释行（//、/*、*/、 * 、/**）避免「修复说明」误报
# 块注释行格式：` * xxx`（前导空格 + 星号），单行注释 `// xxx`，多行注释开头 `/** xxx`
# 注：grep -E (POSIX ERE) 不支持 \s，用 [ \t] 等价
CORS_HITS=$(grep -rnE "(allowedOrigins|@CrossOrigin|Access-Control-Allow-Origin)" \
    --include="*.java" \
    "$PROJECT_ROOT/backend/src/main/java" 2>/dev/null \
    | grep -vE ":[[:space:]]*(\*|//|/\*\*|/\*|\*/)" \
    | grep -vE ":[[:space:]]+\*[[:space:]]" \
    || true)

if [ -n "$CORS_HITS" ]; then
  echo "$CORS_HITS" | head -20
  HIT_COUNT=$(echo "$CORS_HITS" | wc -l | tr -d ' ')
  echo -e "  ${YELLOW}→ 命中 $HIT_COUNT 处（应在 application.yml 集中配置）${NC}"
else
  echo -e "  ${GREEN}→ 无 CORS 硬编码 ✓${NC}"
fi

# ── 3. 对比 port-allocation.md 真源表 ──
echo
echo -e "${YELLOW}[3/3] 对比 port-allocation.md 真源表${NC}"
if [ ! -f "$ALLOCATION_FILE" ]; then
  echo -e "  ${RED}→ 缺少 $ALLOCATION_FILE（请先生成）${NC}"
  EXIT_CODE=1
else
  echo -e "  ${GREEN}→ 真源表存在 ✓${NC}"
  if [ "$DIFF_MODE" = true ]; then
    echo "  详细 diff 模式（待扩展）"
  fi
fi

# ── 总结 ──
echo
echo -e "${BLUE}═══════════════════════════════════════════════${NC}"
if [ $EXIT_CODE -eq 0 ]; then
  echo -e "${GREEN}  ✓ 端口一致性校验通过${NC}"
else
  echo -e "${RED}  ✗ 端口一致性校验失败${NC}"
  echo
  echo "  修复建议："
  echo "  1. 主站 frontend 端口硬编码 → 改 meta[name=\"api-base\"] + Nginx sub_filter（V2.0.77.23）"
  echo "  2. 主站 backend application*.yml 端口 → 已经是单一真源（合法）"
  echo "  3. GEOM frontend 端口 → 改 .env.development/staging/production + --mode"
  echo "  4. CORS 硬编码 → 抽到 application.yml 的 lingyao.cors.allowed-origins 配置项"
  echo "  5. 详细 SOP 见：infra/port-change-checklist.md"
fi
echo -e "${BLUE}═══════════════════════════════════════════════${NC}"
exit $EXIT_CODE
