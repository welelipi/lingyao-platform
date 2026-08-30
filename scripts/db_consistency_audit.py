#!/usr/bin/env python3
"""
db_consistency_audit.py - 凌瑶空间数据库一致性审计工具（v1.0 · 2026-08-30）

按 P1-1（db-selection-spec.md）决策树审计 7 组件数据库符合度：
1. 多租户 → 必须 PG
2. 数据规模（>5GB）→ 必须 PG
3. 跨服务/分布式 → 必须 PG
4. 单租户 < 100MB → H2 内存（dev only）
5. 单租户 100M-5G → SQLite（嵌入式轨）

输出：
- 文本表格（人读）
- JSON（机器读）
- 退出码：0=全合规 / 1=有警告 / 2=有违规
"""
import json
import os
import sqlite3
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Tuple


# 7 组件数据库配置
PROJECTS: Dict[str, Dict] = {
    "lingyao-main": {
        "name": "凌瑶主站",
        "code": "—",
        "port_backend": 9091,
        "db_type": "H2+PG",
        "db_paths": {
            "dev (H2)": "jdbc:h2:mem:lingyao",
            "prod (PG)": "jdbc:postgresql://localhost:5432/lingyao",
        },
        "expected_track": "main-pg",  # 主航道
        "container": "lingyao-postgres",
    },
    "geom-prism": {
        "name": "GEOM（棱镜）",
        "code": "棱镜",
        "port_backend": 8090,
        "db_type": "SQLite",
        "db_paths": {
            "default": "/Users/hua/Documents/myself/凌瑶/geom/sqlite-data/geom.db",
        },
        "expected_track": "embedded-sqlite",
    },
    "mpd-prism": {
        "name": "MPDM（皓元）",
        "code": "皓元",
        "port_backend": 8100,
        "db_type": "SQLite",
        "db_paths": {
            "default": "/Users/hua/Documents/myself/MPD-myself/backend/data/hospital_hoyuan.db",
        },
        "expected_track": "embedded-sqlite",
    },
    "aidd-source": {
        "name": "AIDD（源策）",
        "code": "源策",
        "port_backend": 18080,
        "db_type": "PostgreSQL",
        "db_paths": {
            "default": "jdbc:postgresql://localhost:5432/aidd",
        },
        "expected_track": "main-pg",
    },
    "porm-mingshu": {
        "name": "POR-M（明枢）",
        "code": "明枢",
        "port_backend": 8280,
        "db_type": "SQLite",
        "db_paths": {
            "default": "/Users/hua/Documents/myself/POR-M/明枢 智能协作平台/10_Backend/mingshu-backend/sqlite-data/mingshu.db",
        },
        "expected_track": "embedded-sqlite",
    },
    "dinfo-chenlu": {
        "name": "D-Info（辰录）",
        "code": "辰录",
        "port_backend": 8281,
        "db_type": "H2+PG",
        "db_paths": {
            "dev (H2)": "jdbc:h2:mem:dinfo",
            "prod (PG)": "jdbc:postgresql://localhost:5432/dinfo",
        },
        "expected_track": "main-pg",
    },
    "med-ppt": {
        "name": "MED-PPT",
        "code": "未启",
        "port_backend": None,
        "db_type": "未启动",
        "db_paths": {},
        "expected_track": "main-pg",  # 启动时按 P1-1 决策树推荐
    },
}


# P1-1 决策树 Q1-Q4
DECISION_TREE = """
P1-1 决策树（凌瑶空间 DB 选型）：

Q1: 数据可丢？
  - ✅ 是（开发/测试/重启可清零）→ H2 内存（dev profile）
  - ❌ 否（生产/长期持久化）→ Q2

Q2: 多租户？
  - ✅ 是（> 1 个独立数据 schema）→ PostgreSQL（主航道轨）
  - ❌ 否（单租户）→ Q3

Q3: 数据规模？
  - < 100MB → H2 内存（单测）/ SQLite（嵌入式）
  - 100M - 5GB → SQLite（嵌入式轨，默认）
  - > 5GB → PostgreSQL（主航道轨，必须）
  - 接近边界（> 4GB）→ 提前规划拆库或迁 PG

Q4: 跨服务/分布式？
  - ✅ 是（多服务共享 schema）→ PostgreSQL（主航道轨）
  - ❌ 否（单服务）→ 选前面结果
"""


def audit_sqlite_file(db_path: str) -> Dict:
    """审计单个 SQLite 文件的实际状态"""
    result = {
        "exists": False,
        "size_mb": 0,
        "tables": 0,
        "journal_mode": "unknown",
        "busy_timeout": "unknown",
        "sync": "unknown",
        "warnings": [],
    }

    if not os.path.exists(db_path):
        result["warnings"].append(f"file not found: {db_path}")
        return result

    result["exists"] = True
    result["size_mb"] = round(os.path.getsize(db_path) / 1024 / 1024, 2)

    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        cur = conn.cursor()

        # 表数
        cur.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
        result["tables"] = cur.fetchone()[0]

        # PRAGMA 探测
        for pragma_name in ["journal_mode", "busy_timeout", "synchronous"]:
            cur.execute(f"PRAGMA {pragma_name}")
            val = cur.fetchone()[0]
            result[pragma_name.replace("synchronous", "sync")] = val

        conn.close()
    except Exception as e:
        result["warnings"].append(f"sqlite open failed: {e}")

    # 数据规模检查（Q3）
    if result["size_mb"] > 5 * 1024:
        result["warnings"].append(
            f"🚨 规模 {result['size_mb']:.0f}MB > 5GB，按 Q3 必须迁 PG（主航道轨）"
        )
    elif result["size_mb"] > 4 * 1024:
        result["warnings"].append(
            f"⚠️ 规模 {result['size_mb']:.0f}MB 接近 5GB 边界，建议提前规划拆库或迁 PG"
        )

    # journal_mode 检查
    if result["journal_mode"] != "wal":
        result["warnings"].append(
            f"⚠️ journal_mode={result['journal_mode']}，未开 WAL（高并发会有锁冲突）"
        )

    return result


def audit_project(project_key: str, project_info: Dict) -> Dict:
    """审计单个项目"""
    audit = {
        "key": project_key,
        "name": project_info["name"],
        "code": project_info["code"],
        "port_backend": project_info["port_backend"],
        "db_type": project_info["db_type"],
        "expected_track": project_info["expected_track"],
        "dbs": [],
        "compliant": True,
        "warnings": [],
    }

    # 决策树判断
    expected = project_info["expected_track"]
    db_type = project_info["db_type"]

    if expected == "main-pg" and "PG" not in db_type and "PostgreSQL" not in db_type:
        audit["warnings"].append(
            f"⚠️ 应走主航道 PG 轨（{db_type} 当前不符，需确认）"
        )

    if expected == "embedded-sqlite" and "SQLite" not in db_type:
        audit["warnings"].append(
            f"⚠️ 应走嵌入式 SQLite 轨（{db_type} 当前不符）"
        )

    # 审计每个数据库文件
    for label, db_path in project_info.get("db_paths", {}).items():
        if db_path.startswith("jdbc:"):
            audit["dbs"].append({
                "label": label,
                "path": db_path,
                "type": "jdbc",
                "audit": {"exists": "remote", "warnings": []},
            })
        else:
            db_audit = audit_sqlite_file(db_path)
            audit["dbs"].append({
                "label": label,
                "path": db_path,
                "type": "sqlite",
                "audit": db_audit,
            })

    # 汇总
    if audit["warnings"]:
        audit["compliant"] = False

    return audit


def render_text(audits: List[Dict]) -> str:
    """渲染文本表格输出"""
    lines = []
    lines.append("=" * 80)
    lines.append("凌瑶空间数据库一致性审计报告")
    lines.append("=" * 80)
    lines.append("")

    # 决策树参考
    lines.append("📋 P1-1 决策树（db-selection-spec.md §3）：")
    lines.append("  Q1 数据可丢 → H2 内存（dev）")
    lines.append("  Q2 多租户 → PG 主航道轨")
    lines.append("  Q3 规模 > 5GB → PG / < 100MB H2 / 100M-5G SQLite")
    lines.append("  Q4 跨服务 → PG")
    lines.append("")
    lines.append("=" * 80)

    for audit in audits:
        status = "✅ 合规" if audit["compliant"] else "❌ 不合规"
        lines.append(f"\n【{audit['name']}】({audit['code']}) 端口 {audit['port_backend']} {status}")
        lines.append(f"  DB 类型: {audit['db_type']}  期望轨: {audit['expected_track']}")

        for db in audit["dbs"]:
            lines.append(f"  📂 {db['label']}: {db['path']}")
            if db["type"] == "sqlite":
                a = db["audit"]
                if a["exists"]:
                    lines.append(f"     存在: ✅  大小: {a['size_mb']}MB  表数: {a['tables']}")
                    lines.append(
                        f"     PRAGMA: journal_mode={a['journal_mode']}, "
                        f"busy_timeout={a['busy_timeout']}, synchronous={a['sync']}"
                    )
                else:
                    lines.append(f"     ❌ 不存在")

        for warning in audit["warnings"]:
            lines.append(f"  {warning}")

    lines.append("")
    lines.append("=" * 80)
    lines.append("汇总")
    lines.append("=" * 80)

    total = len(audits)
    compliant = sum(1 for a in audits if a["compliant"])
    lines.append(f"  总组件: {total}")
    lines.append(f"  合规: {compliant}")
    lines.append(f"  不合规: {total - compliant}")

    total_warnings = sum(len(a["warnings"]) for a in audits)
    lines.append(f"  总告警: {total_warnings}")

    return "\n".join(lines)


def main():
    import argparse
    parser = argparse.ArgumentParser(
        description="凌瑶空间数据库一致性审计工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=DECISION_TREE,
    )
    parser.add_argument("--json", action="store_true", help="JSON 输出")
    parser.add_argument("--quiet", "-q", action="store_true", help="只输出告警")
    parser.add_argument("--no-color", action="store_true", help="禁用颜色")
    parser.add_argument("--project", "-p", help="只审计指定项目 key")
    args = parser.parse_args()

    # 审计
    projects_to_audit = PROJECTS
    if args.project:
        if args.project not in PROJECTS:
            print(f"❌ 未知项目: {args.project}")
            print(f"   可用: {', '.join(PROJECTS.keys())}")
            sys.exit(2)
        projects_to_audit = {args.project: PROJECTS[args.project]}

    audits = [audit_project(key, info) for key, info in projects_to_audit.items()]

    # 输出
    if args.json:
        print(json.dumps(audits, ensure_ascii=False, indent=2))
    else:
        if args.quiet:
            for audit in audits:
                if audit["warnings"]:
                    print(f"\n【{audit['name']}】")
                    for w in audit["warnings"]:
                        print(f"  {w}")
        else:
            print(render_text(audits))

    # 退出码
    has_violation = any(not a["compliant"] for a in audits)
    has_warning = any(len(a["warnings"]) > 0 for a in audits)

    if has_violation:
        sys.exit(2)
    elif has_warning:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == "__main__":
    main()
