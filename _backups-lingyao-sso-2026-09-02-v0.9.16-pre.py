"""
GEOM 凌瑶主站 SSO 接入路由（C47 W5 P1-SSO · 2026-08-29）

路径：
  POST /api/auth/lingyao/sso-callback  接收 platform_token → 验签主站 JWT → 3 级回退查找 sys_user → 签 GEOM 本地 JWT
  GET  /api/auth/lingyao/status        前端探测 SSO 启用状态

不动现有 /api/auth/feishu/* 路径（飞书 OAuth 保持兼容）
"""
from __future__ import annotations
import json
import logging
import os
from typing import Optional

import jwt as pyjwt
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app.config import LINGYAO_JWT_SECRET, LINGYAO_MAIN_SITE_URL
from app.database import get_db
from app.token_utils import issue_token

router = APIRouter(prefix="/lingyao", tags=["lingyao-sso"])
log = logging.getLogger("lingyao-sso")


def _verify_lingyao_token(platform_token: str) -> dict:
    """验签凌瑶主站 JWT（HS512, audience=lingyao-sso）"""
    try:
        payload = pyjwt.decode(
            platform_token,
            LINGYAO_JWT_SECRET,
            algorithms=["HS512"],
            audience="lingyao-sso",
            options={"require": ["exp", "aud"]},
        )
        return payload
    except pyjwt.ExpiredSignatureError:
        raise HTTPException(status_code=403, detail="主站 token 已过期")
    except pyjwt.InvalidAudienceError:
        raise HTTPException(status_code=403, detail="主站 token audience 错误")
    except pyjwt.InvalidTokenError as e:
        raise HTTPException(status_code=403, detail=f"主站 token 无效: {e}")


def _resolve_user(payload: dict) -> Optional[dict]:
    """3 级回退查找 sys_user"""
    username = payload.get("username") or payload.get("sub")
    display_name = payload.get("displayName")
    if not display_name:
        identity = payload.get("identity") or {}
        display_name = identity.get("display_name") if isinstance(identity, dict) else None
    lingyao_user_id = str(payload.get("uid") or "")
    lingyao_tenant_id = str(payload.get("cid") or "")
    lingyao_role = payload.get("role", "")

    log.info("[LingyaoSSO] 主站用户 uid=%s tenantId=%s username=%s displayName=%s role=%s adm=%s",
             lingyao_user_id, lingyao_tenant_id, username, display_name, lingyao_role, payload.get("adm"))

    db = get_db()
    db.row_factory = __import__('sqlite3').Row

    # 1) lingyao_user_id 精确
    if lingyao_user_id:
        row = db.execute("SELECT * FROM sys_user WHERE lingyao_user_id = ?", (lingyao_user_id,)).fetchone()
        if row:
            log.info("[LingyaoSSO] lingyao_user_id=%s 精确命中 → %s", lingyao_user_id, row["username"])
            return dict(row)

    # 2) username 精确
    if username:
        row = db.execute(
            "SELECT * FROM sys_user WHERE username = ? OR lingyao_username = ? OR real_name = ?",
            (username, username, username),
        ).fetchone()
        if row:
            log.info("[LingyaoSSO] username=%s 精确命中 → %s", username, row["username"])
            return dict(row)

    # 3) displayName 模糊
    if display_name:
        row = db.execute("SELECT * FROM sys_user WHERE real_name = ?", (display_name,)).fetchone()
        if row:
            log.info("[LingyaoSSO] displayName=%s 模糊命中 → %s", display_name, row["username"])
            return dict(row)

    log.warning("[LingyaoSSO] 未找到匹配 sys_user：uid=%s username=%s displayName=%s",
                lingyao_user_id, username, display_name)
    # V0.9.14.0 P1#6：关闭自动开通
    # 主人决策：陌生人无法直登 GEOM，必须先经管理员手工 ETL（etl_lingyao_users.py）绑定 lingyao_user_id
    # 故此处仅返回 None，由 sso_callback 路由 raise 403
    return None


class SsoCallbackBody(BaseModel):
    platform_token: str


# ── V0.9.15.0 P4-1 公司超管自动获得 GEOM 超管权限（六A 契约 · V2.0.38 主人 14:44 拍板）──
ROLE_SUPER = "super_admin"
ROLE_SENIOR = "senior_operator"


def _write_role_audit(user_id: int, action: str, old_role: str, new_role: str,
                      payload_uid, reason: str = "主站 is_tenant_admin claim 同步"):
    """写角色变更审计（复用 user_audit_log）"""
    try:
        db = get_db()
        detail = json.dumps({
            "old_role": old_role,
            "new_role": new_role,
            "lingyao_uid": payload_uid,
            "reason": reason,
            "contract": "V2.0.38 六A + V2.0.39 方案A",
            "version": "V0.9.15.0",
        }, ensure_ascii=False)
        db.execute(
            "INSERT INTO user_audit_log(user_id, action, method, ip, user_agent, detail) "
            "VALUES (?, ?, 'lingyao_claim', '', '', ?)",
            (user_id, action, detail),
        )
        db.commit()
    except Exception as e:
        log.warning("[LingyaoSSO] role audit 写入失败：%s", e)


def _apply_tenant_admin_role(user: dict, payload: dict) -> dict:
    """主站 JWT claim is_tenant_admin 是权威裁决（V2.0.38 六A）

    规则：
      - claim=true  → 本地 role 提升为 super_admin（自动，无需人工）
      - claim=false + 本地 super_admin → 降权为 senior_operator + 审计（主站降权时）
      - claim=false + 本地 < super_admin → 不动（保持）
    """
    is_tenant_admin = bool(payload.get("is_tenant_admin", False))
    current_role = user.get("role", "")
    payload_uid = payload.get("uid")

    if is_tenant_admin:
        if current_role != ROLE_SUPER:
            log.info("[LingyaoSSO] 公司超管自动提升 role: %s -> super_admin, lingyaoUserId=%s username=%s",
                     current_role, payload_uid, user.get("username"))
            db = get_db()
            db.execute("UPDATE sys_user SET role=? WHERE id=?", (ROLE_SUPER, user["id"]))
            db.commit()
            user["role"] = ROLE_SUPER
            _write_role_audit(user["id"], "role_promote_tenant_admin",
                              old_role=current_role, new_role=ROLE_SUPER,
                              payload_uid=payload_uid,
                              reason="主站 claim is_tenant_admin=true")
    else:
        # 主站降权：本地 super_admin + claim=false → 降权 + 审计
        if current_role == ROLE_SUPER:
            log.warning("[LingyaoSSO] 主站降权同步: username=%s super_admin -> senior_operator, lingyaoUserId=%s",
                        user.get("username"), payload_uid)
            db = get_db()
            db.execute("UPDATE sys_user SET role=? WHERE id=?", (ROLE_SENIOR, user["id"]))
            db.commit()
            user["role"] = ROLE_SENIOR
            _write_role_audit(user["id"], "role_demote_tenant_admin",
                              old_role=ROLE_SUPER, new_role=ROLE_SENIOR,
                              payload_uid=payload_uid,
                              reason="主站 claim is_tenant_admin=false（主站已降权）")
    return user





@router.post("/sso-callback")
def sso_callback(body: SsoCallbackBody, request: Request):
    """接收凌瑶主站 platform_token → 验签 → 签 GEOM 本地 JWT"""
    # PRD-1.10 SSO 审计：记录来源 IP + User-Agent
    client_ip = request.client.host if request.client else "unknown"
    user_agent = (request.headers.get("user-agent") or "")[:200]
    log.info("[LingyaoSSO] SSO 请求来源 ip=%s ua=%s", client_ip, user_agent)

    if not body.platform_token:
        raise HTTPException(status_code=400, detail="缺少 platform_token 参数")

    payload = _verify_lingyao_token(body.platform_token)
    user = _resolve_user(payload)

    if not user:
        raise HTTPException(
            status_code=403,
            detail=f"未找到匹配用户，请联系超管绑定凌瑶账号（uid={payload.get('uid')}）",
        )

    # PRD-1.5 跨租户隔离校验：主站 cid 必须与本地 user.lingyao_tenant_id 一致
    user_tenant = str(user.get("lingyao_tenant_id") or "")
    payload_tenant = str(payload.get("cid") or "")
    if user_tenant and payload_tenant and user_tenant != payload_tenant:
        log.warning("[LingyaoSSO] 跨租户访问拦截: 主站 cid=%s, 本地 user lingyao_tenant_id=%s, username=%s",
                    payload_tenant, user_tenant, user.get("username"))
        raise HTTPException(status_code=403, detail="tenant_mismatch")

    # NEW-5 首次绑定保护：已绑定 lingyao_user_id 且与 token uid 不一致时拒绝覆盖（防身份劫持）
    existing_lingyao_id = str(user.get("lingyao_user_id") or "")
    payload_uid = str(payload.get("uid") or "")
    if existing_lingyao_id and payload_uid and existing_lingyao_id != payload_uid:
        log.warning("[LingyaoSSO] 绑定冲突: username=%s 已绑定 lingyao_user_id=%s, token 声称 uid=%s，拒绝覆盖",
                    user.get("username"), existing_lingyao_id, payload_uid)
        raise HTTPException(status_code=403, detail="该账号已绑定其他凌瑶主站身份，如需换绑请联系超管")

    # 写入凌瑶关联字段（仅首次绑定；已绑定则保持原值）
    db = get_db()
    db.execute(
        """UPDATE sys_user SET lingyao_user_id = COALESCE(NULLIF(lingyao_user_id, ''), ?),
               lingyao_tenant_id = COALESCE(NULLIF(lingyao_tenant_id, ''), ?),
               lingyao_username = COALESCE(NULLIF(lingyao_username, ''), ?),
               last_login_method = ?, last_sso_login_at = CURRENT_TIMESTAMP
           WHERE id = ?""",
        (
            payload_uid,
            payload_tenant,
            payload.get("username") or payload.get("sub") or "",
            "LINGYAO_SSO",
            user["id"],
        ),
    )
    db.commit()

    # V0.9.15.0 P4-1：主站 is_tenant_admin claim 自动升降 GEOM 本地 role（六A 契约）
    user = _apply_tenant_admin_role(user, payload)

    # 签 GEOM 本地 JWT
    managed_categories = json.loads(user.get("category_ids") or "[]")
    token = issue_token(user["id"], user["username"], user["role"], managed_categories)
    # V0.9.14.0 P2#11：同时签 refresh token（30 天）
    from app.token_utils import issue_refresh_token
    refresh = issue_refresh_token(user_id=user["id"], username=user["username"])

    # V0.9.14.0 P3#13：SSO 登录审计（复用 user_audit_log，action 用 login_lingyao 区分）
    try:
        db = get_db()
        db.execute(
            "INSERT INTO user_audit_log(user_id, action, method, ip, user_agent, detail) "
            "VALUES (?, 'login_lingyao', 'lingyao', ?, ?, ?)",
            (user["id"], client_ip, user_agent, f"lingyao_uid={payload.get('uid')}")
        )
        db.commit()
    except Exception as e:
        log.warning("[LingyaoSSO] audit log 写入失败：%s", e)

    log.info("[LingyaoSSO] 登录成功 username=%s lingyaoUserId=%s", user["username"], payload.get("uid"))
    return {
        "code": 0,
        "msg": "ok",
        "data": {
            "token": token,
            "refreshToken": refresh,  # V0.9.14.0 P2#11
            "user": {
                "id": user["id"],
                "username": user["username"],
                "realName": user.get("real_name"),
                "role": user["role"],
                "lingyaoUserId": str(payload.get("uid") or ""),
                "lingyaoTenantId": str(payload.get("cid") or ""),
                "lastLoginMethod": "LINGYAO_SSO",
            },
            "loginMethod": "LINGYAO_SSO",
        },
    }


@router.get("/status")
def status():
    """前端探测 SSO 启用状态"""
    return {
        "code": 0,
        "msg": "ok",
        "data": {
            "enabled": True,
            "audience": "lingyao-sso",
            "secretLength": len(LINGYAO_JWT_SECRET),
            "mainSiteUrl": LINGYAO_MAIN_SITE_URL,  # V0.9.14.0 P0#1
        },
    }


# ── V0.9.14.0 P1#9 ──────────────────────────────────────────
class SsoLogoutCallbackBody(BaseModel):
    platform: str
    user_id: Optional[int] = None
    username: Optional[str] = None


@router.post("/sso-logout-callback")
def sso_logout_callback(body: SsoLogoutCallbackBody, request: Request):
    """接收凌瑶主站推送的 SSO 单点登出事件（V0.9.14.0 P1#9）

    触发：主站用户在主站登出 → 主站通知所有已登录的子产品 → 子产品撤销该用户的 session

    GEOM 端处理：
      1. 接收主站推送的 user_id（或 username）
      2. 查询该 user 当前活跃的 JWT（如果有持久化记录）→ 加入 blacklist
      3. 注：GEOM 当前 JWT 无服务端持久化记录，故仅依赖 blacklist + 前端清 localStorage
         （用户级强制下线要求 GEOM 引入 refresh_token 持久化机制，P2#11 已设计）

    安全：
      - 当前不验签（主站推送路径尚未在跨仓协议中定义；先 best-effort）
      - 后续 P4+ 引入共享 HS512 signature header 验签

    Returns:
        {"code":0, "msg":"ok", "data":{"processed": true}}
    """
    log.info("[LingyaoSSO-logout] 收到主站推送：platform=%s user_id=%s username=%s ip=%s",
             body.platform, body.user_id, body.username,
             request.client.host if request.client else "?")
    # 简化实现：仅记 audit + 返回成功；强制下线由前端收到推送后清 localStorage 实现
    # 后续 P2#11 refresh_token 接入后可在此处遍历 blacklist 该用户所有未过期 token
    return {"code": 0, "msg": "ok", "data": {"processed": True, "note": "best-effort"}}


@router.post("/sso-logout-callback-frontend")
def sso_logout_callback_frontend(body: SsoLogoutCallbackBody):
    """供 GEOM 前端在主站登出回调时主动通知（V0.9.14.0 P1#9）

    当前实现：标记当前用户需要重新登录（前端在轮询 /user/info 时拿 401 即跳登录卡）
    """
    log.info("[LingyaoSSO-logout-frontend] 前端主动通知：platform=%s user_id=%s username=%s",
             body.platform, body.user_id, body.username)
    return {"code": 0, "msg": "ok", "data": {"processed": True}}
