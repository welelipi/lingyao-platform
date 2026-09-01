#!/usr/bin/env python3
"""
凌瑶空间 PG 数据库健康监控（护栏 3）
=====================================

5 项探测：
1. PG 进程 / 端口可达性
2. 多 db 存在性（lingyao / msl_ma_db / 其他）
3. 每个 db 大小（避免无限增长）
4. 连接数（active + idle，避免超 max_connections）
5. 长事务（> 5min，防止 idle_in_transaction 锁竞争）

退出码：
- 0 = 全绿
- 1 = 有黄（warn）
- 2 = 有红（crit）

JSON 输出（--json）便于 CI/CD / cron 集成。

2026-08-30 凌瑶空间数据库治理 · 护栏 3
作者：主流程 AI · 沉淀自 MPDM SQLite db_monitor.py 模式
"""
from __future__ import annotations
import argparse
import json
import os
import socket
import sys
from datetime import datetime, timezone, timedelta
from typing import Any

import psycopg

# 东八区
TZ_CN = timezone(timedelta(hours=8))


def now_iso() -> str:
    return datetime.now(TZ_CN).isoformat(timespec="seconds")


def probe(host: str, port: int, user: str, password: str, db: str) -> dict[str, Any]:
    """对单个 db 跑 5 项探测，返回 dict（含 status: green/yellow/red）。"""
    out: dict[str, Any] = {
        "host": host,
        "port": port,
        "db": db,
        "user": user,
        "checked_at": now_iso(),
    }

    # 1) 可达性
    try:
        sock = socket.create_connection((host, port), timeout=5)
        sock.close()
        out["reachable"] = True
    except Exception as e:
        out["reachable"] = False
        out["error"] = f"socket: {e}"
        out["status"] = "red"
        return out

    # 2-N) 连 db 查
    try:
        conn = psycopg.connect(
            host=host, port=port, user=user, password=password, dbname=db,
            connect_timeout=5,
        )
    except Exception as e:
        out["db_connect"] = False
        out["error"] = f"psycopg: {e}"
        out["status"] = "red"
        return out

    out["db_connect"] = True
    try:
        with conn.cursor() as cur:
            # 版本
            cur.execute("SELECT version()")
            v = cur.fetchone()
            out["version"] = v[0].split(" on ")[0] if v else "?"

            # 当前 db 大小（pg_database_size）
            cur.execute("SELECT pg_database_size(current_database())")
            size_bytes = cur.fetchone()[0]
            out["db_size_mb"] = round(size_bytes / 1024 / 1024, 2)

            # 连接数（active + idle）
            cur.execute("""
                SELECT state, count(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                GROUP BY state
            """)
            conn_stats = {state or "unknown": cnt for state, cnt in cur.fetchall()}
            out["connections"] = conn_stats
            total_conn = sum(conn_stats.values())
            out["total_connections"] = total_conn

            # 长事务（> 5 min）
            cur.execute("""
                SELECT count(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND state IN ('idle in transaction', 'active')
                  AND xact_start IS NOT NULL
                  AND now() - xact_start > interval '5 minutes'
            """)
            long_tx = cur.fetchone()[0]
            out["long_transactions"] = long_tx

            # 表数
            cur.execute("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
            """)
            out["table_count"] = cur.fetchone()[0]

    except Exception as e:
        out["error"] = f"query: {e}"
        out["status"] = "red"
    finally:
        conn.close()

    # 状态评级
    status = "green"
    if not out.get("db_connect"):
        status = "red"
    elif out.get("long_transactions", 0) > 0:
        status = "yellow"
    elif out.get("total_connections", 0) > 80:  # PG 默认 max_connections=100，80 警戒
        status = "yellow"
    elif out.get("db_size_mb", 0) > 1024:  # > 1GB 黄警
        status = "yellow"

    out["status"] = status
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="凌瑶空间 PG 健康监控（护栏 3）")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--user", default="msl_ma")
    parser.add_argument("--password", default="msl_ma_pwd")
    parser.add_argument(
        "--db", action="append", default=[],
        help="要探测的 db 名（可多次指定，默认 msl_ma_db + lingyao）",
    )
    parser.add_argument("--json", action="store_true", help="输出 JSON 格式")
    args = parser.parse_args()

    dbs = args.db or ["msl_ma_db", "lingyao"]

    results = []
    worst = 0
    for db in dbs:
        try:
            r = probe(args.host, args.port, args.user, args.password, db)
        except Exception as e:
            r = {"db": db, "status": "red", "error": str(e), "checked_at": now_iso()}
        results.append(r)
        s = r.get("status", "red")
        if s == "red":
            worst = 2
        elif s == "yellow" and worst < 1:
            worst = 1

    payload = {
        "checked_at": now_iso(),
        "host": args.host,
        "port": args.port,
        "databases": results,
        "worst_status": ["green", "yellow", "red"][worst],
    }

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        # 人类可读
        print(f"\n=== 凌瑶 PG 健康监控 · {payload['checked_at']} ===")
        print(f"目标: {args.host}:{args.port}")
        for r in results:
            icon = {"green": "🟢", "yellow": "🟡", "red": "🔴"}.get(r.get("status", "red"), "?")
            print(f"\n{icon} [{r.get('status', 'red').upper()}] db={r.get('db', '?')}")
            if r.get("error"):
                print(f"   ❌ error: {r['error']}")
                continue
            print(f"   version: {r.get('version', '?')}")
            print(f"   db_size: {r.get('db_size_mb', '?')} MB")
            print(f"   tables: {r.get('table_count', '?')}")
            print(f"   connections: total={r.get('total_connections', '?')} | {r.get('connections', {})}")
            print(f"   long_tx: {r.get('long_transactions', 0)}")
        print(f"\n→ overall: {payload['worst_status'].upper()}")
    return worst


if __name__ == "__main__":
    sys.exit(main())