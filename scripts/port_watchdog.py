#!/usr/bin/env python3
"""凌瑶端口 watchdog — 备用主流程核心：持续监控关键端口，挂了自动调 daemon 重启。

监控目标（按主子流程优先级）：
  9091 — 凌瑶主仓后端（Spring Boot private profile）
  8765 — 凌瑶主仓前端（Python http.server，website/）
  8100 — MPDM 后端（独立项目 MPD-myself 的 FastAPI uvicorn）
  3100 — MPDM 前端（独立项目 MPD-myself 的 node vite）

设计原则：
  - 探测间隔 30s（避免给活服务加压）
  - 探测失败 2 次才触发重启（避免抖动误杀）
  - 重启后给 15s 启动缓冲，再验证一次
  - 全部事件落 logs/watchdog.log（含时间戳、原因、动作）
  - 后端用 daemon 脚本重启（start_backend_daemon.py / start_frontend_daemon.py）
  - MPDM 不在本仓内，restart 走 MPD-myself 的脚本（先查脚本存在）

不做的事：
  - 不主动 push（铁律）
  - 不改代码、不动 git
  - 不重启主人 SSH 才能看到的远端服务（铁律）
  - 不杀活的进程（先 kill_existing 只在 dev 守护脚本里有）
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path("/Users/hua/Documents/myself/凌瑶")
MPD_ROOT = Path("/Users/hua/Documents/myself/MPD-myself")
DINFO_ROOT = Path("/Users/hua/Documents/myself/Dinfo")
AIDD_ROOT = Path("/Users/hua/Documents/myself/AIDD/ai-project-copilot")
PORM_ROOT = Path("/Users/hua/Documents/myself/POR-M/明枢 智能协作平台")
WATCHDOG_LOG = PROJECT_ROOT / "logs" / "watchdog.log"

# 凌瑶空间 V3.1 架构 = 1 主站 + 6 子模块（代号：棱镜/皓元/源策/明枢/辰录/MED-PPT）
# (name, port, health_url, restart_cmd, restart_cwd, startup_grace_sec)
TARGETS = [
    # ── 主网站 ──
    {
        "name": "凌瑶主站后端",
        "port": 9091,
        "url": "http://127.0.0.1:9091/api/version",
        "restart_cmd": ["python3", "scripts/start_backend_daemon.py"],
        "restart_cwd": str(PROJECT_ROOT),
        "grace_sec": 20,
        "check_http": True,
    },
    {
        "name": "凌瑶主站前端",
        "port": 8765,
        "url": "http://127.0.0.1:8765/",
        "restart_cmd": ["python3", "scripts/start_frontend_daemon.py"],
        "restart_cwd": str(PROJECT_ROOT),
        "grace_sec": 6,
        "check_http": True,
    },
    # ── 棱镜（GEOM）──
    {
        "name": "棱镜后端",
        "port": 8090,
        "url": "http://127.0.0.1:8090/",
        # 棱镜后端是 FastAPI，主仓无启动器；只 ping 不重启
        "restart_cmd": None,
        "restart_cwd": str(PROJECT_ROOT / "geom" / "geo-backend-py"),
        "grace_sec": 12,
        "check_http": True,
    },
    {
        "name": "棱镜前端",
        "port": 5182,  # 2026-08-30 改：5180 IPv6 被 sandbox broker 占
        "url": "http://127.0.0.1:5182/",
        "restart_cmd": ["python3", "scripts/start_geom_frontend_daemon.py"],
        "restart_cwd": str(PROJECT_ROOT),
        "grace_sec": 8,
        "check_http": True,
    },
    # ── 皓元（MPDM）──
    {
        "name": "皓元后端",
        "port": 8100,
        "url": "http://127.0.0.1:8100/docs",
        "restart_cmd": None,  # MPDM 独立项目，主仓不直接管
        "restart_cwd": str(MPD_ROOT),
        "grace_sec": 12,
        "check_http": True,
    },
    {
        "name": "皓元前端",
        "port": 3100,
        "url": "http://127.0.0.1:3100/",
        "restart_cmd": ["python3", "scripts/start_mpdm_frontend_daemon.py"],
        "restart_cwd": str(MPD_ROOT),
        "grace_sec": 8,
        "check_http": True,
    },
    # ── 源策（AIDD）──
    {
        "name": "源策后端",
        "port": 18080,
        "url": "http://127.0.0.1:18080/",
        "restart_cmd": None,
        "restart_cwd": str(AIDD_ROOT),
        "grace_sec": 15,
        "check_http": True,
    },
    {
        "name": "源策前端",
        "port": 13000,
        "url": "http://127.0.0.1:13000/",
        "restart_cmd": None,
        "restart_cwd": str(AIDD_ROOT),
        "grace_sec": 8,
        "check_http": True,
    },
    # ── 明枢（POR-M）──
    {
        "name": "明枢后端",
        "port": 8280,
        "url": "http://127.0.0.1:8280/",
        "restart_cmd": None,
        "restart_cwd": str(PORM_ROOT),
        "grace_sec": 15,
        "check_http": True,
    },
    {
        "name": "明枢前端",
        "port": 3190,
        "url": "http://127.0.0.1:3190/",
        "restart_cmd": None,
        "restart_cwd": str(PORM_ROOT),
        "grace_sec": 8,
        "check_http": True,
    },
    # ── 辰录（D-Info）──
    {
        "name": "辰录后端",
        "port": 8281,
        "url": "http://127.0.0.1:8281/",
        "restart_cmd": None,
        "restart_cwd": str(DINFO_ROOT),
        "grace_sec": 15,
        "check_http": True,
    },
    {
        "name": "辰录前端",
        "port": 5181,
        "url": "http://127.0.0.1:5181/",
        "restart_cmd": None,
        "restart_cwd": str(DINFO_ROOT),
        "grace_sec": 8,
        "check_http": True,
    },
    # ── MED-PPT（暂无服务）──
    # 等主人启服务后再加
]

PROBE_INTERVAL = 30       # 正常探测间隔（秒）
FAIL_THRESHOLD = 2        # 连续失败 N 次才触发重启
STARTUP_GRACE_EXTRA = 5   # 重启后额外等几秒再验证


def log(msg: str) -> None:
    line = f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}"
    print(line, flush=True)
    WATCHDOG_LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(WATCHDOG_LOG, "a") as f:
        f.write(line + "\n")


def probe(target: dict) -> tuple[bool, str]:
    """探测端口 + 健康检查。返回 (ok, 详情)。"""
    port = target["port"]
    # 1. lsof 端口
    try:
        out = subprocess.run(
            ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN"],
            capture_output=True, text=True, timeout=3,
        ).stdout
        if f":{port} (LISTEN)" not in out:
            return False, f":{port} 未监听"
    except subprocess.TimeoutExpired:
        return False, "lsof 超时"

    # 2. HTTP 健康检查（必须绕过 sandbox http_proxy，否则 IPv6 服务会被代理误报 502）
    if target.get("check_http"):
        try:
            r = subprocess.run(
                ["curl", "-s", "--noproxy", "*", "-m", "5",
                 "-o", "/dev/null", "-w", "%{http_code}",
                 target["url"]],
                capture_output=True, text=True, timeout=7,
            )
            code = r.stdout.strip()
            # 2026-08-30 修订：401/403/404 也判"服务在跑"
            # 401 = 鉴权拒（说明服务活 + 有 endpoint，只是没传 token）
            # 403 = 鉴权拒（类似）
            # 404 = 服务在但路径不对（不应该误判挂）
            # 只 5xx（服务器错误）或 000（连不上）才算真挂
            if not code or (code[0] not in ("2", "3", "4")):
                return False, f"HTTP {code or 'no-response'}"
        except subprocess.TimeoutExpired:
            return False, "curl 超时"
        except Exception as e:
            return False, f"curl 异常 {e}"
    return True, "OK"


def restart(target: dict, reason: str) -> bool:
    """调 daemon 脚本重启服务。返回是否成功。"""
    log(f"🔧 [{target['name']}] 重启触发 · 原因: {reason}")
    cmd = target.get("restart_cmd")
    cwd = target.get("restart_cwd", str(PROJECT_ROOT))
    if not cmd:
        log(f"⚠️  [{target['name']}] 未配置 restart_cmd（独立项目，不在凌瑶主仓），跳过自动重启")
        return False
    try:
        r = subprocess.run(
            cmd, cwd=cwd,
            capture_output=True, text=True, timeout=10,
        )
        log(f"   └─ 退出码={r.returncode} stdout={r.stdout.strip()[:200]}")
    except Exception as e:
        log(f"   └─ 重启异常: {e}")
        return False
    return True


def main() -> int:
    log("=" * 60)
    log("🐕 凌瑶端口 watchdog 启动（备用主流程）")
    log(f"   监控目标: {len(TARGETS)} 个")
    log(f"   探测间隔: {PROBE_INTERVAL}s · 失败阈值: {FAIL_THRESHOLD} 次")
    log(f"   日志: {WATCHDOG_LOG}")
    for t in TARGETS:
        log(f"   · {t['name']} :{t['port']} ({t['url']})")

    fail_count = {t["name"]: 0 for t in TARGETS}
    cycle = 0

    while True:
        cycle += 1
        for t in TARGETS:
            ok, detail = probe(t)
            if ok:
                if fail_count[t["name"]] > 0:
                    log(f"✅ [{t['name']}] 恢复 · {detail}")
                fail_count[t["name"]] = 0
            else:
                fail_count[t["name"]] += 1
                log(f"⚠️  [{t['name']}] 失败 {fail_count[t['name']]}/{FAIL_THRESHOLD} · {detail}")
                if fail_count[t["name"]] >= FAIL_THRESHOLD:
                    if restart(t, detail):
                        time.sleep(t["grace_sec"] + STARTUP_GRACE_EXTRA)
                        # 验证重启结果
                        ok2, detail2 = probe(t)
                        if ok2:
                            log(f"✅ [{t['name']}] 重启成功 · {detail2}")
                            fail_count[t["name"]] = 0
                        else:
                            log(f"❌ [{t['name']}] 重启后仍异常 · {detail2}")
                    fail_count[t["name"]] = 0  # 不论如何重置计数，避免狂重启
        time.sleep(PROBE_INTERVAL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("🐕 watchdog 收到 SIGINT 退出")
        sys.exit(0)