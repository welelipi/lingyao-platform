#!/usr/bin/env python3
"""GEOM supervisor 守护启动器：双重 fork + setsid，脱离 sandbox 父进程。
与凌瑶主站 scripts/start_backend_daemon.py 同款手法。
supervisor.py 自带 while True 监控循环 + 密钥文件 fallback，本脚本只负责把它变成 daemon。
"""
import os, sys

GEOM_DIR = "/Users/hua/Documents/myself/凌瑶/geom/geo-backend-py"
PYTHON = os.path.join(GEOM_DIR, "venv/bin/python")
LOG_FILE = "/Users/hua/Documents/myself/凌瑶/geom/logs/supervisor-daemon.out"

pid = os.fork()
if pid > 0:
    print(f"[geom-daemon] spawned pid={pid}, parent exiting", flush=True)
    sys.exit(0)

os.setsid()

pid = os.fork()
if pid > 0:
    sys.exit(0)

with open(os.devnull, "rb", 0) as f:
    os.dup2(f.fileno(), sys.stdin.fileno())
os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
log_fd = os.open(LOG_FILE, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(log_fd, sys.stdout.fileno())
os.dup2(log_fd, sys.stderr.fileno())

os.chdir(GEOM_DIR)
os.execv(PYTHON, [PYTHON, os.path.join(GEOM_DIR, "supervisor.py")])
