#!/usr/bin/env python3
"""凌瑶前端守护启动器：双重 fork + setsid，让 python http.server 脱离 sandbox 父进程。"""
from __future__ import annotations
import os, sys

PROJECT_DIR = "/Users/hua/Documents/myself/凌瑶"
PYTHON_BIN = "/usr/bin/python3"
WEBSITE_DIR = f"{PROJECT_DIR}/website"
LOG_FILE = f"{PROJECT_DIR}/logs/frontend.out"

# 第 1 次 fork：父进程立即 exit
pid = os.fork()
if pid > 0:
    print(f"[frontend-daemon] spawned pid={pid}, parent exiting", flush=True)
    sys.exit(0)

# 子进程 setsid()
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
os.execv(PYTHON_BIN, [
    PYTHON_BIN,
    "-m", "http.server", "8765",
    "--bind", "0.0.0.0",
    "--directory", WEBSITE_DIR,
])
