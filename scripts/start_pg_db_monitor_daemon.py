#!/usr/bin/env python3
"""
凌瑶空间 PG 健康监控守护启动器（护栏 3 · 双重 fork + setsid）

2026-08-30 凌瑶空间数据库治理 · 护栏 3
"""
from __future__ import annotations
import os, sys, time, subprocess

# === 配置（按容器实际 env 改）===
LOG = "/Users/hua/Documents/myself/凌瑶/logs/pg-db-monitor.out"
PID_FILE = "/tmp/pg-db-monitor.pid"
INTERVAL_SEC = 300  # 5 分钟一探测
PYTHON = "/Users/hua/.workbuddy/binaries/python/envs/med-ppt/bin/python"
SCRIPT = "/Users/hua/Documents/myself/凌瑶/scripts/pg_db_monitor.py"
USER = "lingyao_app"
PASSWORD = "Ui/kBL01bvoIfz6Rctgpx8uD"  # 与 docker inspect lingyao-postgres 一致
DBS = ["lingyao", "msl_ma_db"]


# 第 1 次 fork
pid = os.fork()
if pid > 0:
    print(f"[daemon] spawned pid={pid}, parent exiting")
    sys.exit(0)

os.setsid()

# 第 2 次 fork
pid = os.fork()
if pid > 0:
    sys.exit(0)

# 孙子进程 = 真正 daemon
sys.stdout.flush()
sys.stderr.flush()

with open(os.devnull, "rb", 0) as f:
    os.dup2(f.fileno(), sys.stdin.fileno())

os.makedirs(os.path.dirname(LOG), exist_ok=True)
log_fd = os.open(LOG, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(log_fd, sys.stdout.fileno())
os.dup2(log_fd, sys.stderr.fileno())

os.makedirs(os.path.dirname(PID_FILE), exist_ok=True)
with open(PID_FILE, "w") as f:
    f.write(str(str((os.getpid()))))

print(f"[daemon {time.strftime('%Y-%m-%d %H:%M:%S')}] MED-PPT/凌瑶 PG db-monitor started")
print(f"[daemon] target: {USER}@{DBS}, interval={INTERVAL_SEC}s")

try:
    while True:
        cmd = [
            PYTHON, SCRIPT,
            "--user", USER,
            "--password", PASSWORD,
        ]
        for db in DBS:
            cmd.extend(["--db", db])

        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            print("=" * 60)
            print(f"[probe {time.strftime('%Y-%m-%d %H:%M:%S')}] exit={r.returncode}")
            if r.stdout:
                print(r.stdout.rstrip())
            if r.stderr:
                print("[stderr]", r.stderr.rstrip())
        except subprocess.TimeoutExpired:
            print(f"[probe {time.strftime('%Y-%m-%d %H:%M:%S')}] TIMEOUT (> 60s)")
        except Exception as e:
            print(f"[probe {time.strftime('%Y-%m-%d %H:%M:%S')}] ERROR: {e}")

        time.sleep(INTERVAL_SEC)
except KeyboardInterrupt:
    print("[daemon] KeyboardInterrupt, exiting")