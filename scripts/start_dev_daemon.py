#!/usr/bin/env python3
"""凌瑶 dev profile 守护启动器（双重 fork + setsid）。

dev profile 特点：
- H2 内存数据库（jdbc:h2:mem:lingyao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL）
- 每次重启数据清零（适合本地开发）
- spring.profiles.active=dev（默认，无需 override）
- 启用 H2 console（http://localhost:9091/h2-console）
- 端口 9091（与 prod 共享端口，启动前会先杀旧进程）

用法：
    python3 scripts/start_dev_daemon.py                # 默认启动
    python3 scripts/start_dev_daemon.py --h2-console   # 显式开 H2 console
    python3 scripts/start_dev_daemon.py --no-h2        # 关 H2 console
"""
from __future__ import annotations

import argparse
import os
import sys

# 路径参数化（V2.0.11 Fix 2 上传远端代码库稳定性）：用 LINGYAO_HOME 覆盖 macOS 本地路径
# 用法：LINGYAO_HOME=/opt/lingyao python3 scripts/start_dev_daemon.py
PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")
JAVA_BIN = os.environ.get("LINGYAO_JAVA_BIN", "/Users/hua/sdk/jdk-21/Contents/Home/bin/java")
JAR_PATH = f"{PROJECT_DIR}/backend/target/lingyao-platform.jar"
LOG_FILE = f"{PROJECT_DIR}/logs/dev-backend.out"
PID_FILE = f"{PROJECT_DIR}/backend/dev-backend.pid"
SERVER_PORT = int(os.environ.get("LINGYAO_DEV_PORT", "9091"))  # Spring web + H2 console 共享端口


def kill_existing() -> None:
    """先杀旧的 dev 后端进程，避免端口冲突。"""
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE) as f:
                old_pid = int(f.read().strip())
            os.kill(old_pid, 15)  # SIGTERM
            print(f"[dev-daemon] sent SIGTERM to old pid={old_pid}", flush=True)
        except (ProcessLookupError, ValueError, OSError):
            pass
        try:
            os.remove(PID_FILE)
        except OSError:
            pass

    # 兜底：lsof 杀 9091 占用
    import subprocess
    try:
        out = subprocess.run(
            ["lsof", "-ti", f":{SERVER_PORT}"],
            capture_output=True, text=True, timeout=3,
        ).stdout.strip()
        for p in out.splitlines():
            try:
                os.kill(int(p), 15)
                print(f"[dev-daemon] killed leftover pid={p} on :{SERVER_PORT}", flush=True)
            except (ProcessLookupError, ValueError):
                pass
    except (subprocess.TimeoutExpired, FileNotFoundError):
        pass


def main() -> None:
    parser = argparse.ArgumentParser(description="凌瑶 dev 后端守护启动器")
    parser.add_argument("--h2-console", dest="h2_console", action="store_true",
                        default=True, help="启用 H2 web console（默认开）")
    parser.add_argument("--no-h2", dest="h2_console", action="store_false",
                        help="关闭 H2 web console")
    parser.add_argument("--port", type=int, default=SERVER_PORT,
                        help=f"Spring web 端口（默认 {SERVER_PORT}）")
    parser.add_argument("--h2-port", type=int, default=SERVER_PORT,
                        help=f"H2 console 端口（默认 {SERVER_PORT}，与 Spring web 共享）")
    args = parser.parse_args()

    # 启动前先杀旧进程
    kill_existing()

    # 第 1 次 fork：父进程立即 exit
    pid = os.fork()
    if pid > 0:
        print(f"[dev-daemon] spawned pid={pid}, parent exiting", flush=True)
        # 把孙子进程的 PID 写到 PID_FILE（实际是孙子 PID，但父进程退出后孙子 PID 仍可追踪）
        try:
            with open(PID_FILE, "w") as f:
                f.write(str(pid))
        except OSError:
            pass
        sys.exit(0)

    # 子进程 setsid()：变成新 session leader
    os.setsid()

    # 第 2 次 fork：确保不是 session leader
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
        JAVA_BIN,
        "-Xms256m", "-Xmx768m",  # dev 内存小一点
        "-jar", JAR_PATH,
        f"--server.port={args.port}",
        "--spring.profiles.active=dev",  # 显式声明（虽然默认就是 dev）
        f"--spring.h2.console.enabled={'true' if args.h2_console else 'false'}",
        "--spring.h2.console.path=/h2-console",
        "--spring.datasource.url=jdbc:h2:mem:lingyao;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    ]
    os.execv(JAVA_BIN, cmd)


if __name__ == "__main__":
    main()
