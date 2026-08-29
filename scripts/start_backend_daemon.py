#!/usr/bin/env python3
"""凌瑶后端守护启动器：双重 fork + setsid，让 java -jar 脱离 sandbox 父进程。"""
from __future__ import annotations
import os, sys

# 路径参数化（V2.0.11 Fix 2 上传远端代码库稳定性）：用 LINGYAO_HOME 覆盖 macOS 本地路径
# 用法：LINGYAO_HOME=/opt/lingyao python3 scripts/start_backend_daemon.py
PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")
JAVA_BIN = os.environ.get("LINGYAO_JAVA_BIN", "/Users/hua/sdk/jdk-21/Contents/Home/bin/java")
JAR_PATH = f"{PROJECT_DIR}/backend/target/lingyao-platform.jar"
CONFIG_PATH = f"{PROJECT_DIR}/backend/src/main/resources/application-private.yml"
LOG_FILE = f"{PROJECT_DIR}/logs/backend.out"

# 第 1 次 fork：父进程立即 exit，让子进程被 init 收养
pid = os.fork()
if pid > 0:
    print(f"[backend-daemon] spawned pid={pid}, parent exiting", flush=True)
    sys.exit(0)

# 子进程 setsid()：变成新 session leader
os.setsid()

# 第 2 次 fork：确保不是 session leader
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
os.execv(JAVA_BIN, [
    JAVA_BIN,
    "-jar", JAR_PATH,
    "--server.port=9091",
    "--spring.profiles.active=private",
    f"--spring.config.location={CONFIG_PATH}",
])
