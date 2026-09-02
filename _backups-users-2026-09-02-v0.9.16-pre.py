"""
用户管理路由（2026-07-20 三角色权限模型升级）

角色枚举：super_admin / senior_operator / operator
权限：仅超级管理员可管理用户

2026-07-20 新增：
  - 飞书字段 (feishu_union_id/email/department)
  - 重置密码端点
  - 分类权限同步到 user_category_permission 表
"""
from typing import List
import json

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.database import get_db
from app.password_utils import hash_password
from app.permissions import (
    require_super_admin, CurrentUser, ROLE_SUPER, ROLE_SENIOR, ROLE_OPER,
    get_current_user,
)

router = APIRouter(tags=["Users"])

VALID_ROLES = [ROLE_SUPER, ROLE_SENIOR, ROLE_OPER]


def _normalize_category_scope(db, role: str, raw_category_ids: str) -> str:
    """规范并校验角色的分类范围。

    senior_operator：空值代表全部分类只读；非空可选多个分类。
    operator：必须选择 ≥1 个分类（fail closed：空授权绝不能解释成全平台权限）；
              2026-08-13 由主人决定放开为可多分类，底层 user_category_permission / can_write
              早已支持多分类，零下游改动。
    super_admin：分类字段不参与授权，统一清空。
    """
    if role == ROLE_SUPER:
        return ""

    raw_parts = [part.strip() for part in (raw_category_ids or "").split(",") if part.strip()]
    if any(not part.isdigit() or int(part) <= 0 for part in raw_parts):
        raise HTTPException(400, "分类参数格式错误")
    category_ids = sorted({int(part) for part in raw_parts})

    if role == ROLE_OPER and not category_ids:
        raise HTTPException(400, "普通操作员必须至少选择 1 个可访问分类")

    if category_ids:
        placeholders = ",".join("?" for _ in category_ids)
        existing = {
            int(row["id"])
            for row in db.execute(
                f"SELECT id FROM brand_category WHERE id IN ({placeholders})",
                category_ids,
            ).fetchall()
        }
        missing = [category_id for category_id in category_ids if category_id not in existing]
        if missing:
            raise HTTPException(400, f"分类不存在: {','.join(str(item) for item in missing)}")

    return ",".join(str(category_id) for category_id in category_ids)


def _sync_role_category_permissions(db, user_id: int, role: str, category_ids: str, granted_by: int):
    """按角色写入权限表；角色本身仍是最终读写闸门。"""
    db.execute("DELETE FROM user_category_permission WHERE user_id=?", (user_id,))
    if role == ROLE_SENIOR and category_ids:
        _sync_category_permissions(db, user_id, category_ids, "read", granted_by)
    elif role == ROLE_OPER:
        _sync_category_permissions(db, user_id, category_ids, "write", granted_by)


class UserCreate(BaseModel):
    username: str
    password: str = ""
    real_name: str = ""
    role: str = ROLE_OPER
    category_ids: str = ""
    feishu_union_id: str = ""
    feishu_email: str = ""
    department: str = ""
    status: int = 1


class UserUpdate(BaseModel):
    username: str
    password: str = ""
    real_name: str = ""
    role: str = ROLE_OPER
    category_ids: str = ""
    feishu_union_id: str = ""
    feishu_email: str = ""
    department: str = ""
    status: int = 1


class PwdReset(BaseModel):
    new_password: str


def _sync_category_permissions(db, user_id: int, category_ids: str, perm_type: str, granted_by: int):
    """同步 user_category_permission 表（先删后建）。"""
    db.execute("DELETE FROM user_category_permission WHERE user_id=? AND perm_type=?", (user_id, perm_type))
    cats = [int(c) for c in category_ids.split(",") if c.strip().isdigit()]
    for cat_id in cats:
        db.execute(
            "INSERT INTO user_category_permission(user_id, category_id, perm_type, granted_by) VALUES (?,?,?,?)",
            (user_id, cat_id, perm_type, granted_by),
        )


@router.get("")
def list_users(_: CurrentUser = Depends(require_super_admin)):
    """列出所有用户（仅超管）。

    V0.9.14.0 P3#7：补 lingyao_user_id / lingyao_username 字段，
    让 Settings.vue 用户管理 Tab 能展示凌瑶绑定状态 + 在 UI 内绑定/解绑。
    """
    db = get_db()
    rows = db.execute(
        "SELECT id, username, real_name, role, category_ids, "
        "       feishu_union_id, feishu_email, department, "
        "       lingyao_user_id, lingyao_username, "  # V0.9.14.0 P3#7
        "       last_login_at, last_login_method, status, created_at "
        "FROM sys_user ORDER BY id"
    ).fetchall()
    return [dict(r) for r in rows]


@router.post("")
def create_user(
    body: UserCreate,
    user: CurrentUser = Depends(require_super_admin),
):
    """新增用户（仅超管）。

    同时写入：
      - sys_user 表（用户基本信息）
      - user_category_permission 表（分类权限，多行）
      - user_audit_log（审计日志）
    """
    if body.role not in VALID_ROLES:
        raise HTTPException(400, f"无效角色: {body.role}（可选: {', '.join(VALID_ROLES)}）")

    db = get_db()
    category_ids = _normalize_category_scope(db, body.role, body.category_ids)
    pwd = hash_password(body.password) if body.password else ""
    algo = "bcrypt" if pwd else "sha256-v1"

    db.execute(
        "INSERT INTO sys_user (username, password, real_name, role, category_ids, "
        "       feishu_union_id, feishu_email, department, status, password_algo) "
        "VALUES (?,?,?,?,?, ?,?,?,?, ?)",
        (body.username, pwd, body.real_name, body.role,
         category_ids, body.feishu_union_id, body.feishu_email,
         body.department, body.status, algo),
    )
    new_id = db.execute("SELECT last_insert_rowid()").fetchone()[0]

    if body.role == "senior_operator":
        db.execute("UPDATE sys_user SET push_enabled=0 WHERE id=?", (new_id,))

    _sync_role_category_permissions(db, new_id, body.role, category_ids, user.user_id)

    # 审计
    db.execute(
        "INSERT INTO user_audit_log(user_id, action, target_user_id, method, detail) "
        "VALUES (?, 'create_user', ?, 'admin', ?)",
        (user.user_id, new_id,
         f"role={body.role} cats={category_ids} feishu={body.feishu_union_id}"),
    )
    db.commit()
    return {"success": True, "id": new_id}


@router.put("/{uid}")
def update_user(
    uid: int,
    body: UserUpdate,
    user: CurrentUser = Depends(require_super_admin),
):
    """更新用户信息（仅超管）。"""
    if body.role not in VALID_ROLES:
        raise HTTPException(400, f"无效角色: {body.role}（可选: {', '.join(VALID_ROLES)}）")

    db = get_db()
    category_ids = _normalize_category_scope(db, body.role, body.category_ids)
    db.execute(
        "UPDATE sys_user SET username=?, real_name=?, role=?, category_ids=?, "
        "       feishu_union_id=?, feishu_email=?, department=?, "
        "       status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
        (body.username, body.real_name, body.role, category_ids,
         body.feishu_union_id, body.feishu_email, body.department,
         body.status, uid),
    )
    if body.password:
        db.execute(
            "UPDATE sys_user SET password=?, password_algo='bcrypt' WHERE id=?",
            (hash_password(body.password), uid),
        )

    # 同步分类权限
    _sync_role_category_permissions(db, uid, body.role, category_ids, user.user_id)

    # 审计
    db.execute(
        "INSERT INTO user_audit_log(user_id, action, target_user_id, method, detail) "
        "VALUES (?, 'update_user', ?, 'admin', ?)",
        (user.user_id, uid,
         f"role={body.role} cats={category_ids}"),
    )
    db.commit()
    return {"success": True}


@router.delete("/{uid}")
def delete_user(
    uid: int,
    user: CurrentUser = Depends(require_super_admin),
):
    """删除用户（仅超管）。"""
    db = get_db()
    db.execute("DELETE FROM sys_user WHERE id=?", (uid,))
    db.execute("DELETE FROM user_category_permission WHERE user_id=?", (uid,))
    db.execute(
        "INSERT INTO user_audit_log(user_id, action, target_user_id, method) "
        "VALUES (?, 'delete_user', ?, 'admin')",
        (user.user_id, uid),
    )
    db.commit()
    return {"success": True}


@router.post("/{uid}/reset-password")
def reset_password(
    uid: int,
    body: PwdReset,
    user: CurrentUser = Depends(require_super_admin),
):
    """重置用户密码（仅超管）。

    2026-07-20 新增：支持双登录（密码+飞书）兜底。
    """
    db = get_db()
    db.execute(
        "UPDATE sys_user SET password=?, password_algo='bcrypt' WHERE id=?",
        (hash_password(body.new_password), uid),
    )
    db.execute(
        "INSERT INTO user_audit_log(user_id, action, target_user_id, method) "
        "VALUES (?, 'password_reset', ?, 'admin')",
        (user.user_id, uid),
    )
    db.commit()
    return {"success": True}


class PushEnabledReq(BaseModel):
    enabled: bool

@router.get("/me/push-enabled")
def get_push_enabled(user: CurrentUser = Depends(get_current_user)):
    db = get_db()
    row = db.execute(
        "SELECT push_enabled FROM sys_user WHERE id=?", (user.user_id,)
    ).fetchone()
    return {"pushEnabled": bool(row["push_enabled"]) if row else True}

@router.put("/me/push-enabled")
def set_push_enabled(
    body: PushEnabledReq,
    user: CurrentUser = Depends(get_current_user),
):
    db = get_db()
    db.execute(
        "UPDATE sys_user SET push_enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
        (1 if body.enabled else 0, user.user_id),
    )
    db.commit()
    return {"success": True, "pushEnabled": body.enabled}

class ReportEnabledReq(BaseModel):
    enabled: bool

class ReportCfgReq(BaseModel):
    categories: dict

@router.get("/me/report-monthly-enabled")
def get_report_monthly_enabled(user: CurrentUser = Depends(get_current_user)):
    db = get_db()
    row = db.execute(
        "SELECT report_monthly_cfg FROM sys_user WHERE id=?", (user.user_id,)
    ).fetchone()
    cfg = json.loads(row["report_monthly_cfg"] or "{}") if row else {}
    cat_ids = user.managed_categories
    defaults = {str(c): cfg.get(str(c), True) for c in cat_ids if c != -1}
    return {"categories": defaults}

@router.put("/me/report-monthly-enabled")
def set_report_monthly_enabled(
    body: ReportCfgReq,
    user: CurrentUser = Depends(get_current_user),
):
    db = get_db()
    row = db.execute(
        "SELECT report_monthly_cfg FROM sys_user WHERE id=?", (user.user_id,)
    ).fetchone()
    existing = json.loads(row["report_monthly_cfg"] or "{}") if row else {}
    existing.update(body.categories)
    db.execute(
        "UPDATE sys_user SET report_monthly_cfg=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
        (json.dumps(existing), user.user_id),
    )
    db.commit()
    return {"success": True, "categories": existing}

@router.get("/me/report-weekly-enabled")
def get_report_weekly_enabled(user: CurrentUser = Depends(get_current_user)):
    db = get_db()
    row = db.execute(
        "SELECT report_weekly_cfg FROM sys_user WHERE id=?", (user.user_id,)
    ).fetchone()
    cfg = json.loads(row["report_weekly_cfg"] or "{}") if row else {}
    cat_ids = user.managed_categories
    defaults = {str(c): cfg.get(str(c), True) for c in cat_ids if c != -1}
    return {"categories": defaults}

@router.put("/me/report-weekly-enabled")
def set_report_weekly_enabled(
    body: ReportCfgReq,
    user: CurrentUser = Depends(get_current_user),
):
    db = get_db()
    row = db.execute(
        "SELECT report_weekly_cfg FROM sys_user WHERE id=?", (user.user_id,)
    ).fetchone()
    existing = json.loads(row["report_weekly_cfg"] or "{}") if row else {}
    existing.update(body.categories)
    db.execute(
        "UPDATE sys_user SET report_weekly_cfg=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
        (json.dumps(existing), user.user_id),
    )
    db.commit()
    return {"success": True, "categories": existing}
