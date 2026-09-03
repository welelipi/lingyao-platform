#!/usr/bin/env python3
"""凌瑶后端守护启动器：双重 fork + setsid，让 java -jar 脱离 sandbox 父进程。"""
from __future__ import annotations
import os, sys

# 路径参数化（V2.0.11 Fix 2 上传远端代码库稳定性）：用 LINGYAO_HOME 覆盖 macOS 本地路径
# 用法：LINGYAO_HOME=/opt/lingyao python3 scripts/start_backend_daemon.py
# V2.0.29：profile 参数化——LINGYAO_PROFILE=dev 走 H2 内存开发模式（默认 private 私有化）
# V2.0.53：LINGYAO_PROFILE=dev-pg 走本地 Docker PG 持久化开发库（lingyao_dev，界面修改重启保留）
PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")
JAVA_BIN = os.environ.get("LINGYAO_JAVA_BIN", "/Users/hua/sdk/jdk-21/Contents/Home/bin/java")
PROFILE = os.environ.get("LINGYAO_PROFILE", "private")
JAR_PATH = f"{PROJECT_DIR}/backend/target/lingyao-platform.jar"
CONFIG_PATH = os.environ.get(
    "LINGYAO_CONFIG",
    f"{PROJECT_DIR}/backend/src/main/resources/application-{PROFILE}.yml",
)
LOG_FILE = os.environ.get("LINGYAO_LOG", f"{PROJECT_DIR}/logs/backend-{PROFILE}.out")

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

# ════════════════════════════════════════════════════════════
# V2.0.14 PRD-1.2 修复：非 dev profile 必须有 LINGYAO_JWT_SECRET
# 仿 backend/docker/entrypoint.sh 设计：持久化到 data/.jwt_secret
# - 第一次启动生成密钥（openssl rand 48 字节 base64）
# - 后续启动复用同一份 secret（保证 SSO token 重启后仍有效）
# ════════════════════════════════════════════════════════════
JWT_SECRET_FILE = f"{PROJECT_DIR}/data/.jwt_secret"

if not os.environ.get("LINGYAO_JWT_SECRET"):
    if os.path.exists(JWT_SECRET_FILE):
        # 复用持久化的密钥
        with open(JWT_SECRET_FILE, "r") as f:
            os.environ["LINGYAO_JWT_SECRET"] = f.read().strip()
    else:
        # 首次启动：生成新密钥并持久化
        import secrets
        generated = secrets.token_urlsafe(48)  # 48 字节随机 → base64 64 字符
        os.makedirs(os.path.dirname(JWT_SECRET_FILE), exist_ok=True)
        with open(JWT_SECRET_FILE, "w") as f:
            f.write(generated)
        os.chmod(JWT_SECRET_FILE, 0o600)
        os.environ["LINGYAO_JWT_SECRET"] = generated
        print(f"[backend-daemon] 首次启动：生成 JWT 密钥（持久化到 {JWT_SECRET_FILE}）", flush=True)

os.chdir(PROJECT_DIR)
# V2.0.29：dev profile 不指定 config.location（走 jar 内 application.yml 的 dev 多文档段），
# 其余 profile 显式指定外部配置；--server.port=9091 命令行兜底覆盖 sandbox 注入的 SERVER_PORT
# V2.0.53：dev-pg（本地 Docker PG 持久化开发库）同走外部配置，数据重启保留
if PROFILE == "dev":
    os.execv(JAVA_BIN, [
        JAVA_BIN,
        "-jar", JAR_PATH,
        "--server.port=9091",
        "--spring.profiles.active=dev",
    ])
else:
    # V2.0.54 修复：config.location 会【整体替换】默认配置清单，jar 内 application.yml
    # 的共享段（app.version / lingyao.* / security.* 等）全部丢失 → /api/version 回落 1.0.0。
    # 改用 config.additional-location【叠加】：外部 profile 文件优先级更高，共享段仍从 jar 内读取
    os.execv(JAVA_BIN, [
        JAVA_BIN,
        "-jar", JAR_PATH,
        "--server.port=9091",
        "--spring.profiles.active=" + PROFILE,
        f"--spring.config.additional-location={CONFIG_PATH}",
    ])
