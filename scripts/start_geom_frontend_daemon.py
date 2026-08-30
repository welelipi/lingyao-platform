#!/usr/bin/env python3
"""棱镜（GEOM）前端守护启动器：双 fork + setsid，跑 vite 启 dev server。

- 工作目录：~/Documents/myself/凌瑶/geom/geo-frontend/
- 端口：5182（5180 IPv6 被 WorkBuddy sandbox broker 占，绕开；vite.config 已加 host 0.0.0.0）
- 日志：~/Documents/myself/凌瑶/logs/geom-frontend.out
- 启动方式：直接 exec node_modules/.bin/vite（绕过 package.json scripts.dev 写死的 5180）
"""
from __future__ import annotations
import os, sys

PROJECT_DIR = os.environ.get(
    "LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶"
)
GEOM_FRONTEND_DIR = f"{PROJECT_DIR}/geom/geo-frontend"
NODE_BIN = "/Users/hua/.workbuddy/binaries/node/versions/22.12.0/bin/node"
VITE_BIN = f"{GEOM_FRONTEND_DIR}/node_modules/.bin/vite"
LOG_FILE = f"{PROJECT_DIR}/logs/geom-frontend.out"

# 第 1 次 fork
pid = os.fork()
if pid > 0:
    print(f"[geom-frontend-daemon] spawned pid={pid}, parent exiting", flush=True)
    sys.exit(0)

os.setsid()

# 第 2 次 fork
pid = os.fork()
if pid > 0:
    sys.exit(0)

# 孙子进程 = 真正的 daemon
with open(os.devnull, "rb", 0) as f:
    os.dup2(f.fileno(), sys.stdin.fileno())
log_fd = os.open(LOG_FILE, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(log_fd, sys.stdout.fileno())
os.dup2(log_fd, sys.stderr.fileno())
os.close(log_fd)

os.chdir(GEOM_FRONTEND_DIR)

# 2026-08-30 主人下达：
# 1. host=0.0.0.0 → 双栈监听（IPv4 直连可用）
# 2. port=5182 → 绕开 5180 IPv6 被 WorkBuddy sandbox broker 占
os.environ["VITE_PORT"] = "5182"

# 用 execv 替换进程为 vite
# --strictPort 在 vite.config 里已设，无需重复
# --host 不带值 = host: true（IPv4 + IPv6 双栈，与 MPM 保持一致）
os.execv(
    NODE_BIN,
    [NODE_BIN, VITE_BIN, "--host", "--port", "5182"],
)