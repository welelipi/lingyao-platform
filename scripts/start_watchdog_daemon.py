#!/usr/bin/env python3
"""凌瑶 watchdog 守护启动器（双重 fork + setsid）。

让 port_watchdog.py 脱离 sandbox 父进程。
"""
from __future__ import annotations

import os, sys

PROJECT_DIR = "/Users/hua/Documents/myself/凌瑶"
PYTHON_BIN = "/Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3"
WATCHDOG = f"{PROJECT_DIR}/scripts/port_watchdog.py"
LOG_FILE = f"{PROJECT_DIR}/logs/watchdog.out"

# 第 1 次 fork
pid = os.fork()
if pid > 0:
    print(f"[watchdog-daemon] spawned pid={pid}, parent exiting", flush=True)
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

os.chdir(PROJECT_DIR)
os.execv(PYTHON_BIN, [PYTHON_BIN, WATCHDOG])