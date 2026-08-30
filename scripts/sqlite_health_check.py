#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sqlite_health_check.py - 凌瑶空间 SQLite 数据库健康检查工具
==============================================================

适用: POR-M（明枢） / MPDM（皓元） / GEOM（棱镜） / 任何 SQLite 模块

探测项（11 项）:
  1) 文件存在性
  2) 文件大小（>400MB 提示拆库）
  3) 表数（与期望值对比）
  4) PRAGMA journal_mode（应为 wal）
  5) PRAGMA busy_timeout（应为 5000）
  6) PRAGMA synchronous（应为 1/NORMAL，WAL 下安全）
  7) PRAGMA cache_size（应为 -64000 / 64MB）
  8) PRAGMA temp_store（应为 2/MEMORY）
  9) WAL 文件大小（>50MB 触发 checkpoint 提示）
 10) 当前活跃连接数（lsof）
 11) 父目录是否存在（防忘建）

输出: 文本表格（人读） / JSON（机器读）
退出码: 0=全绿 / 1=有黄 / 2=有红

用法:
  # 自动查 MPDM
  python3 sqlite_health_check.py --project mpd

  # 自动查 POR-M
  python3 sqlite_health_check.py --project porm

  # 手动指定路径
  python3 sqlite_health_check.py /path/to/any.db

  # JSON 输出（CI/CD / 监控集成）
  python3 sqlite_health_check.py --project mpd --json

  # 静默模式（只输出告警）
  python3 sqlite_health_check.py --project mpd --quiet

作者: 凌瑶空间 AI · 2026-08-30 · v1.0
对齐: 凌瑶/docs/db-selection-spec.md §6.3 工具复用
"""

import sqlite3
import os
import sys
import json
import argparse
import subprocess
from pathlib import Path
from datetime import datetime


# =========================
# 常量与阈值（可命令行覆盖）
# =========================

DEFAULT_THRESHOLDS = {
    "db_size_mb_warn": 400,        # > 400MB 提示拆业务域
    "wal_size_mb_warn": 50,        # > 50MB 触发 checkpoint 提示
    "busy_timeout_expected": 5000, # P0-2-A 实战值
    "cache_size_expected": -64000, # 64MB page cache
    "synchronous_expected": 1,      # NORMAL（0=FULL/2=OFF）
    "journal_mode_expected": "wal",
    "temp_store_expected": 2,      # MEMORY（0=DEFAULT/1=FILE/2=MEMORY）
}


# 预设项目数据库路径（按 P1-1 §4.2 命名规范）
PROJECT_PATHS = {
    "porm": "/Users/hua/Documents/myself/POR-M/明枢 智能协作平台/10_Backend/mingshu-backend/sqlite-data/mingshu.db",
    "mpd": "/Users/hua/Documents/myself/MPD-myself/backend/data/hospital_hoyuan.db",
    "geom": "/Users/hua/Documents/myself/凌瑶/geom/backend/data/geo.db",
    "med-ppt": "/Users/hua/Documents/myself/MED-PPT/backend/data/med-ppt.db",
}


# ANSI 颜色（无颜色兼容）
class Colors:
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    RED = "\033[91m"
    BLUE = "\033[94m"
    GRAY = "\033[90m"
    RESET = "\033[0m"
    BOLD = "\033[1m"

    @classmethod
    def disable(cls):
        cls.GREEN = cls.YELLOW = cls.RED = cls.BLUE = cls.GRAY = cls.RESET = cls.BOLD = ""


# =========================
# 探测函数
# =========================

def check_file_exists(db_path: str) -> dict:
    """检查文件存在性"""
    p = Path(db_path)
    exists = p.exists()
    return {
        "key": "file_exists",
        "ok": exists,
        "value": str(p.absolute()),
        "level": "ok" if exists else "red",
        "msg": "数据库文件存在" if exists else "❌ 数据库文件不存在！",
    }


def check_db_size(db_path: str, warn_mb: int) -> dict:
    """检查数据库大小"""
    if not Path(db_path).exists():
        return {"key": "db_size", "ok": False, "value": 0, "level": "red", "msg": "文件不存在"}
    size_bytes = os.path.getsize(db_path)
    size_mb = size_bytes / 1024 / 1024
    if size_mb > warn_mb:
        level = "yellow"
        ok = False
        msg = f"⚠️ 数据库 {size_mb:.1f}MB > 阈值 {warn_mb}MB，建议拆业务域"
    else:
        level = "ok"
        ok = True
        msg = f"✅ 数据库 {size_mb:.1f}MB（< {warn_mb}MB）"
    return {"key": "db_size", "ok": ok, "value": round(size_mb, 1), "level": level, "msg": msg}


def check_table_count(conn: sqlite3.Connection, expected: int = None) -> dict:
    """检查表数"""
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
    count = cur.fetchone()[0]
    cur.close()
    if expected is None or count == expected:
        return {"key": "table_count", "ok": True, "value": count, "level": "ok", "msg": f"✅ 表数 = {count}"}
    return {"key": "table_count", "ok": False, "value": count, "level": "yellow",
            "msg": f"⚠️ 表数 = {count}，期望 {expected}"}


def check_pragmas(conn: sqlite3.Connection, thresholds: dict) -> list:
    """检查 PRAGMA 设置（6 项）"""
    results = []
    pragma_checks = [
        ("journal_mode", thresholds["journal_mode_expected"], lambda v: v.lower() == thresholds["journal_mode_expected"]),
        ("busy_timeout", thresholds["busy_timeout_expected"], lambda v: v == thresholds["busy_timeout_expected"]),
        ("synchronous", thresholds["synchronous_expected"], lambda v: v == thresholds["synchronous_expected"]),
        ("cache_size", thresholds["cache_size_expected"], lambda v: v == thresholds["cache_size_expected"]),
        ("temp_store", thresholds["temp_store_expected"], lambda v: v == thresholds["temp_store_expected"]),
    ]
    cur = conn.cursor()
    for pragma, expected, match_fn in pragma_checks:
        cur.execute(f"PRAGMA {pragma}")
        actual = cur.fetchone()[0]
        ok = match_fn(actual)
        if ok:
            results.append({"key": f"pragma_{pragma}", "ok": True, "value": actual, "level": "ok",
                            "msg": f"✅ PRAGMA {pragma} = {actual}"})
        else:
            results.append({"key": f"pragma_{pragma}", "ok": False, "value": actual, "level": "red",
                            "msg": f"❌ PRAGMA {pragma} = {actual}（期望 {expected}）"})
    cur.close()
    return results


def check_wal_size(db_path: str, warn_mb: int) -> dict:
    """检查 WAL 文件大小"""
    wal_path = Path(db_path + "-wal")
    if not wal_path.exists():
        return {"key": "wal_size", "ok": True, "value": 0, "level": "ok",
                "msg": "✅ 无 WAL 文件（journal_mode 非 wal）"}
    size_bytes = wal_path.stat().st_size
    size_mb = size_bytes / 1024 / 1024
    if size_mb > warn_mb:
        return {"key": "wal_size", "ok": False, "value": round(size_mb, 1), "level": "yellow",
                "msg": f"⚠️ WAL {size_mb:.1f}MB > {warn_mb}MB，建议 checkpoint（PRAGMA wal_checkpoint(TRUNCATE)）"}
    return {"key": "wal_size", "ok": True, "value": round(size_mb, 1), "level": "ok",
            "msg": f"✅ WAL {size_mb:.1f}MB（< {warn_mb}MB）"}


def check_active_connections(db_path: str) -> dict:
    """检查活跃连接数（lsof）"""
    try:
        result = subprocess.run(
            ["lsof", db_path],
            capture_output=True, text=True, timeout=5
        )
        lines = result.stdout.strip().split("\n")
        # 减去表头
        count = max(0, len(lines) - 1)
        if count == 0:
            return {"key": "active_connections", "ok": True, "value": 0, "level": "yellow",
                    "msg": "⚠️ 无活跃进程持有数据库（可能未启动？）"}
        if count > 10:
            return {"key": "active_connections", "ok": False, "value": count, "level": "yellow",
                    "msg": f"⚠️ 活跃连接 {count} 个（> 10）"}
        return {"key": "active_connections", "ok": True, "value": count, "level": "ok",
                "msg": f"✅ 活跃连接 {count} 个"}
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return {"key": "active_connections", "ok": True, "value": -1, "level": "ok",
                "msg": "✅ lsof 不可用，跳过（macOS 默认有）"}


def check_parent_dir(db_path: str) -> dict:
    """检查父目录是否存在"""
    p = Path(db_path).parent
    if p.exists():
        return {"key": "parent_dir", "ok": True, "value": str(p), "level": "ok",
                "msg": f"✅ 父目录存在: {p}"}
    return {"key": "parent_dir", "ok": False, "value": str(p), "level": "red",
            "msg": f"❌ 父目录不存在: {p}（SQLite JDBC 不会自动创建！）"}


# =========================
# 主流程
# =========================

def run_health_check(db_path: str, thresholds: dict, expected_tables: int = None) -> dict:
    """运行完整健康检查"""
    results = []

    # 1-3. 文件级检查
    results.append(check_file_exists(db_path))
    results.append(check_parent_dir(db_path))
    results.append(check_db_size(db_path, thresholds["db_size_mb_warn"]))

    # 如果文件不存在，后续跳过
    if not Path(db_path).exists():
        return {"db_path": db_path, "timestamp": datetime.now().isoformat(), "checks": results,
                "summary": {"green": 0, "yellow": 0, "red": sum(1 for r in results if r["level"] == "red"),
                            "exit_code": 2 if any(r["level"] == "red" for r in results) else 0}}

    # 4. WAL 大小
    results.append(check_wal_size(db_path, thresholds["wal_size_mb_warn"]))

    # 5. 连接 + PRAGMA（必须能连）
    # 注意：sqlite3 直连读的是 DB 全局默认 PRAGMA，不反映业务连接级 PRAGMA
    # 若业务用 SQLAlchemy event listener / BeanFactoryPostProcessor 设连接级 PRAGMA，
    # 这里显示的可能是 DB 默认（如 synchronous=2/cache_size=-2000），但业务连接实际正确
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=5)
        results.append(check_table_count(conn, expected_tables))
        results.extend(check_pragmas(conn, thresholds))
        conn.close()
    except sqlite3.OperationalError as e:
        results.append({"key": "connect", "ok": False, "value": None, "level": "red",
                        "msg": f"❌ 无法连接数据库: {e}"})

    # 6. 活跃连接（lsof）
    results.append(check_active_connections(db_path))

    # 汇总
    green = sum(1 for r in results if r["level"] == "ok")
    yellow = sum(1 for r in results if r["level"] == "yellow")
    red = sum(1 for r in results if r["level"] == "red")
    exit_code = 2 if red > 0 else (1 if yellow > 0 else 0)

    return {
        "db_path": db_path,
        "timestamp": datetime.now().isoformat(),
        "checks": results,
        "summary": {"green": green, "yellow": yellow, "red": red, "exit_code": exit_code},
    }


def print_text_report(report: dict, quiet: bool = False):
    """打印文本报告"""
    db_path = report["db_path"]
    summary = report["summary"]

    if not quiet:
        print(f"\n{Colors.BOLD}{'=' * 70}")
        print(f"凌瑶 SQLite 健康检查报告")
        print(f"{'=' * 70}{Colors.RESET}")
        print(f"数据库: {db_path}")
        print(f"时间:   {report['timestamp']}")
        print()

    for check in report["checks"]:
        if quiet and check["level"] == "ok":
            continue
        if check["level"] == "ok":
            color = Colors.GREEN
        elif check["level"] == "yellow":
            color = Colors.YELLOW
        else:
            color = Colors.RED
        print(f"  {color}{check['msg']}{Colors.RESET}")

    if not quiet:
        print()
        print(f"{Colors.BOLD}汇总:{Colors.RESET}")
        print(f"  {Colors.GREEN}绿: {summary['green']}{Colors.RESET}  "
              f"{Colors.YELLOW}黄: {summary['yellow']}{Colors.RESET}  "
              f"{Colors.RED}红: {summary['red']}{Colors.RESET}")
        print()


def main():
    parser = argparse.ArgumentParser(
        description="凌瑶 SQLite 健康检查工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("db_path", nargs="?", help="SQLite 数据库路径（与 --project 二选一）")
    parser.add_argument("--project", "-p", choices=list(PROJECT_PATHS.keys()),
                        help="预设项目: " + ", ".join(PROJECT_PATHS.keys()))
    parser.add_argument("--json", action="store_true", help="JSON 输出")
    parser.add_argument("--quiet", "-q", action="store_true", help="只输出告警")
    parser.add_argument("--no-color", action="store_true", help="禁用颜色")
    parser.add_argument("--db-size-warn", type=int, default=DEFAULT_THRESHOLDS["db_size_mb_warn"],
                        help=f"数据库大小警告阈值（MB，默认 {DEFAULT_THRESHOLDS['db_size_mb_warn']}）")
    parser.add_argument("--wal-size-warn", type=int, default=DEFAULT_THRESHOLDS["wal_size_mb_warn"],
                        help=f"WAL 大小警告阈值（MB，默认 {DEFAULT_THRESHOLDS['wal_size_mb_warn']}）")
    parser.add_argument("--expected-tables", type=int, help="期望表数（可选，用于对比）")

    args = parser.parse_args()

    if args.no_color:
        Colors.disable()

    # 解析路径
    if args.project:
        db_path = PROJECT_PATHS[args.project]
    elif args.db_path:
        db_path = args.db_path
    else:
        parser.error("必须提供 db_path 或 --project")

    thresholds = DEFAULT_THRESHOLDS.copy()
    thresholds["db_size_mb_warn"] = args.db_size_warn
    thresholds["wal_size_mb_warn"] = args.wal_size_warn

    report = run_health_check(db_path, thresholds, args.expected_tables)

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_text_report(report, args.quiet)

    sys.exit(report["summary"]["exit_code"])


if __name__ == "__main__":
    main()
