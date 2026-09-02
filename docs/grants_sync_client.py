#!/usr/bin/env python3
"""
V2.0.39 契约交付物 — 子平台调用主站授权同步接口的参考实现（Python 版，GEOM/其他 Py 后端可直接用）

场景：子平台内审批通过员工的「产品开通」申请后，调主站接口激活 product_user_grant，
     员工下次从主站 /enter 进入直接放行（否则主站仍 403）。

鉴权：用与主站共享的 LINGYAO_JWT_SECRET 签 service JWT（audience=lingyao-service，5 分钟有效）。
     注意 secret 是 HS512 的原始 64 字节（base64url 解码后），本脚本按主站 JwtUtil 同款方式处理。

用法：
    # 同步授权（审批通过后）
    python3 grants_sync_client.py sync --company A-药企 --product AIDD --username a_user1 --approver 张三

    # 撤销授权
    python3 grants_sync_client.py revoke --company A-药企 --product AIDD --username a_user1

    # 主站地址默认 http://localhost:9091，可用 --base-url 覆盖（生产/staging 换域名）
依赖：pip install pyjwt cryptography requests（GEOM venv 已具备）
"""

import argparse
import base64
import sys
import time
import uuid

import jwt  # PyJWT
import requests


def load_secret() -> bytes:
    """与主站 lingyao.jwt.secret 对齐：优先 env LINGYAO_JWT_SECRET（base64url），fallback 读主站 data/.jwt_secret"""
    import os
    env = os.environ.get("LINGYAO_JWT_SECRET")
    if env:
        return base64.urlsafe_b64decode(env + "=" * (-len(env) % 4))
    for p in (
        os.path.expanduser("~/Documents/myself/凌瑶/data/.jwt_secret"),
        os.path.join(os.path.dirname(__file__), "..", "data", ".lingyao-jwt-secret"),
    ):
        if os.path.exists(p):
            return open(p, "rb").read().strip()
    sys.exit("未找到 LINGYAO_JWT_SECRET（env 或主站 data/.jwt_secret）")


def make_service_token(secret: bytes, caller_service: str) -> str:
    now = int(time.time())
    payload = {
        "iss": "lingyao-platform",
        "aud": "lingyao-service",          # 主站JwtUtil.SERVICE_AUDIENCE，必须一致
        "sub": f"service:{caller_service}",
        "svc": caller_service,
        "jti": str(uuid.uuid4()),
        "iat": now,
        "exp": now + 300,                  # 5 分钟
    }
    return jwt.encode(payload, secret, algorithm="HS512")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("action", choices=["sync", "revoke"])
    ap.add_argument("--company", required=True, help="主站公司 code，如 A-药企")
    ap.add_argument("--product", required=True, help="主站产品 code：GEO/HPD/AIDD/DINFO/PORM")
    ap.add_argument("--username", required=True, help="主站用户名")
    ap.add_argument("--approver", default=None, help="子平台侧审批人（选填，进主站审计）")
    ap.add_argument("--reason", default=None, help="撤销原因（选填）")
    ap.add_argument("--base-url", default="http://localhost:9091")
    ap.add_argument("--caller", default=None, help="调用方服务标识（默认取 GEOM）")
    args = ap.parse_args()

    caller = args.caller or "GEOM"
    token = make_service_token(load_secret(), caller)
    path = "/api/internal/grants/sync" if args.action == "sync" else "/api/internal/grants/revoke"
    body = {"companyCode": args.company, "productCode": args.product, "username": args.username}
    if args.approver:
        body["approver"] = args.approver
    if args.reason:
        body["reason"] = args.reason

    resp = requests.post(
        args.base_url + path,
        json=body,
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    print(f"HTTP {resp.status_code}")
    print(resp.text)
    sys.exit(0 if resp.ok else 1)


if __name__ == "__main__":
    main()
