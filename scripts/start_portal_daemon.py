#!/usr/bin/env python3
"""凌瑶主门户后端守护启动器（portal-backend-py · FastAPI · 端口 8765）。

双重 fork + setsid，让 uvicorn 脱离 sandbox 父进程（V2.0.77.7 catch-all 路由后，
主门户走 FastAPI 而非 python http.server，2026-09-09 首次固化）。
"""
from __future__ import annotations
import os, sys

PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")
PYTHON_BIN = os.environ.get(
    "LINGYAO_PYTHON_BIN",
    "/Users/hua/.workbuddy/binaries/python/versions/3.13.12/bin/python3",
)
LOG_FILE = f"{PROJECT_DIR}/logs/portal-backend.out"
PID_FILE = "/tmp/portal-backend.pid"
PORTAL_APP = "portal-backend-py.main:app"
HOST = "0.0.0.0"
PORT = 8765


def kill_existing() -> None:
    """先杀旧 PID + lsof 兜底杀 8765 占用。"""
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE) as f:
                old_pid = int(f.read().strip())
            os.kill(old_pid, 15)
            print(f"[portal-daemon] sent SIGTERM to old pid={old_pid}", flush=True)
        except (ProcessLookupError, ValueError, OSError):
            pass
        try:
            os.remove(PID_FILE)
        except OSError:
            pass

    import subprocess
    try:
        out = subprocess.run(
            ["lsof", "-ti", f":{PORT}"],
            capture_output=True, text=True, timeout=3,
        ).stdout.strip()
        for p in out.splitlines():
            try:
                os.kill(int(p), 15)
                print(f"[portal-daemon] killed leftover pid={p} on :{PORT}", flush=True)
            except (ProcessLookupError, ValueError):
                pass
    except (subprocess.TimeoutExpired, FileNotFoundError):
        pass


def main() -> None:
    kill_existing()

    # 第 1 次 fork：父进程立即 exit
    pid = os.fork()
    if pid > 0:
        print(f"[portal-daemon] spawned pid={pid}, parent exiting", flush=True)
        try:
            with open(PID_FILE, "w") as f:
                f.write(str(pid))
        except OSError:
            pass
        sys.exit(0)

    # 子进程 setsid()
    os.setsid()

    # 第 2 次 fork
    pid = os.fork()
    if pid > 0:
        sys.exit(0)

    # 孙子进程 = 真正的 daemon
    os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
    with open(os.devnull, "rb", 0) as f:
        os.dup2(f.fileno(), sys.stdin.fileno())
    log_fd = os.open(LOG_FILE, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    os.dup2(log_fd, sys.stdout.fileno())
    os.dup2(log_fd, sys.stderr.fileno())

    os.chdir(PROJECT_DIR)

    cmd = [
        PYTHON_BIN,
        "-m", "uvicorn",
        PORTAL_APP,
        "--host", HOST,
        "--port", str(PORT),
        "--log-level", "info",
    ]
    os.execv(PYTHON_BIN, cmd)


if __name__ == "__main__":
    main()