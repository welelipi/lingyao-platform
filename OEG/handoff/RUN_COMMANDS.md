# RUN_COMMANDS.md — 启动 / 停止 / 验证 / 排错

> 全部命令假设在 macOS / zsh，已用 `--noproxy '*'` 绕开 WorkBuddy HTTP_PROXY 环境变量。

## 1. 路径速查（2026-08-11 凌瑶自包含化后，全部基于 ROOT_DIR）

| 用途 | 路径 |
|---|---|
| 凌瑶主项目 | `/Users/hua/Documents/myself/凌瑶` |
| 凌瑶 portal 后端 | `/Users/hua/Documents/myself/凌瑶/portal-backend-py/main.py` |
| 凌瑶 portal 入口 | `http://127.0.0.1:8765/portal` |
| GEO 后端（凌瑶软链接路径） | `/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/main.py` |
| GEO 前端（凌瑶软链接路径） | `/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-frontend` |
| GEO 物理路径（实体重定位后） | `/Users/hua/Documents/myself/凌瑶/_external/OEG/geo-platform` |
| AIDD 前端（凌瑶软链接路径） | `/Users/hua/Documents/myself/凌瑶/products/aidd/frontend` |
| AIDD 物理（暂不搬移） | `/Users/hua/Documents/myself/AIDD/ai-project-copilot` |
| 共享 SQLite | `/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/geo.db` |
| JWT secret | `/Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/.jwt-secret` |
| 接管前备份目录 | `/Users/hua/Documents/myself/凌瑶/OEG/_backup/2026-08-11_pre_relocate` |
| Python 解释器（managed） | `/Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3` |
| Node 解释器（managed） | `/Users/hua/.workbuddy/binaries/node/versions/22.22.2/bin/node` |
| npm 解释器（managed） | `/Users/hua/.workbuddy/binaries/node/versions/22.22.2/bin/npm` |

> **关键：自 2026-08-11 起，`凌瑶/products/geo-platform` 是符号链接，自动指向物理路径（当前 = `凌瑶/_external/OEG/geo-platform`）。
> 所有服务启动命令用凌瑶/products/ 软链接路径，不要直接用原始 `/OEG` 路径。**

## 2. 一键验证（4 端口健康检查）

```bash
for port in 8765 9090 9180 9191; do
  printf "端口 %s : " "$port"
  pid=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null | head -1)
  if [ -n "$pid" ]; then
    code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/")
    echo "PID=$pid HTTP=$code"
  else
    echo "未监听"
  fi
done
```

预期：
- 8765 → 200（portal）
- 9090 → 200（FastAPI /docs 重定向到 /openapi.json）
- 9180 → 200（Vite 根页）
- 9191 → 200（AIDD login.html）

## 3. 服务启动

### 3.1 凌瑶 portal 后端
```bash
cd /Users/hua/Documents/myself/凌瑶/portal-backend-py
nohup /Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3 main.py > /tmp/lingyao-portal.log 2>&1 &
disown
# 或使用 Bash 工具 run_in_background=true 启动
```

### 3.2 GEO 后端
```bash
cd /Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py
nohup /Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3 main.py > /tmp/saas-backend.log 2>&1 &
disown
```

### 3.3 GEO 前端（Vite）
```bash
cd /Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-frontend
nohup /Users/hua/.workbuddy/binaries/node/versions/22.22.2/bin/npm run dev > /tmp/geo-frontend.log 2>&1 &
disown
```

### 3.4 AIDD 前端（静态）
```bash
cd /Users/hua/Documents/myself/凌瑶/products/aidd/frontend
nohup /Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3 -m http.server 9191 > /tmp/aidd-frontend.log 2>&1 &
disown
```

## 4. 服务停止

```bash
for port in 8765 9090 9180 9191; do
  pids=$(lsof -ti:$port 2>/dev/null)
  if [ -n "$pids" ]; then
    echo "杀掉端口 $port 上的进程: $pids"
    kill -9 $pids
  fi
done
```

## 5. SSO 端到端冒烟

```bash
# 1. 凌瑶登录
LOGIN=$(curl --noproxy '*' -s -X POST http://127.0.0.1:8765/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"GEOadmin","password":"lingyao@2026"}')
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# 2. 拿到 GEO 跳转 URL
GEO_URL=$(curl --noproxy '*' -s "http://127.0.0.1:8765/api/portal/redirect/geo?token=$TOKEN&company_id=1&redirect=/action-center" | python3 -c "import sys,json; print(json.load(sys.stdin)['redirect_url'])")

# 3. 验证 GEO 前端
curl --noproxy '*' -s -o /dev/null -w 'GEO 前端 → HTTP %{http_code}\n' "$GEO_URL"

# 4. 验证 GEO 后端接受凌瑶 token
curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:9090/api/platform-hub/dashboard | python3 -m json.tool

# 5. 跨公司隔离
curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:9090/api/reports | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'cid=1 → {len(d) if isinstance(d,list) else 0} 条 reports')"

# 6. 拿到 AIDD 跳转 URL
AIDD_URL=$(curl --noproxy '*' -s "http://127.0.0.1:8765/api/portal/redirect/aidd?token=$TOKEN&company_id=1&redirect=/login.html" | python3 -c "import sys,json; print(json.load(sys.stdin)['redirect_url'])")

# 7. 验证 AIDD 前端含 SSO 代码
curl --noproxy '*' -s "$AIDD_URL" | grep -c consumeSsoFromLingYao | awk '{print "AIDD login.html SSO 代码: " $1 " 处"}'
```

## 6. 排错速查

| 现象 | 根因 | 解决 |
|---|---|---|
| curl 返回 502/504 | WorkBuddy HTTP_PROXY 干扰 | 全部加 `--noproxy '*'` |
| 凌瑶 portal 500 | JWT secret 文件丢失 | 检查 `data/.jwt-secret` 是否在 |
| GEO 后端 401 | token 过期 | 重新走 `api/auth/login` |
| 数据全部看到 | contextvars 漏 set | 检查 `main.py` Middleware |
| 端口冲突 | 上一次进程未清 | `lsof -ti:PORT` 然后 `kill -9` |
| Vite 启动后立刻被 kill | 用 nohup 但父 shell 退出连带 | 用 Bash 工具 `run_in_background=true` |
| AIDD login.html 没 SSO | 文件被覆盖 | 重新注入 `consumeSsoFromLingYao()` |
| 凌瑶 portal 启动报错 | bcrypt / jose 未装 | 用 managed Python 3.13.12 |

## 7. 数据库操作

```bash
# 完整性检查
sqlite3 /Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/geo.db "PRAGMA integrity_check;"

# SaaS 表行数
sqlite3 /Users/hua/Documents/myself/凌瑶/products/geo-platform/geo-backend-py/data/geo.db <<SQL
SELECT 'sys_user' AS t, COUNT(*) AS n FROM sys_user
UNION ALL SELECT 'company', COUNT(*) FROM company
UNION ALL SELECT 'company_user', COUNT(*) FROM company_user
UNION ALL SELECT 'company_invitation', COUNT(*) FROM company_invitation
UNION ALL SELECT 'user_audit_log', COUNT(*) FROM user_audit_log
UNION ALL SELECT 'company_audit_log', COUNT(*) FROM company_audit_log;
SQL
```

## 8. Git 改动核对

```bash
# GEO 仓库当前分支与改动（通过凌瑶软链）
git -C /Users/hua/Documents/myself/凌瑶/products/geo-platform status --short
git -C /Users/hua/Documents/myself/凌瑶/products/geo-platform branch --show-current
git -C /Users/hua/Documents/myself/凌瑶/products/geo-platform diff --stat
```

## 9. WorkBuddy 工作区切换

主人需在 WorkBuddy UI 左侧"工作空间"下拉里：
1. 选 `凌瑶智数`（主项目）。
2. 在 `凌瑶/OEG/` 下建立任务（命名"Geo平台迭代"或主人指定）。
3. 新窗口的 `cwd` 应在 `凌瑶/OEG/`。

---

_本文件由 2026-08-11 14:04 旧窗口生成。_
