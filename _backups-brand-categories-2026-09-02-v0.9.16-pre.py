from fastapi import APIRouter, Depends
from pydantic import BaseModel
from app.database import get_db
from app.routers.admin import verify_admin_user, verify_user  # 2026-07-23：GET 开放给操作员，写端点保持 admin

router = APIRouter(tags=["BrandCategories"])

class BCBody(BaseModel):
    name: str
    product_type: str = "sell"
    sort_order: int = 0

@router.get("")
def list_all(_: dict = Depends(verify_user)):
    rows = get_db().execute("SELECT * FROM brand_category ORDER BY sort_order, id").fetchall()
    return [dict(r) for r in rows]

@router.post("")
def create(body: BCBody, _: dict = Depends(verify_admin_user)):
    db = get_db()
    db.execute("INSERT INTO brand_category (name,product_type,sort_order) VALUES (?,?,?)",
               (body.name, body.product_type, body.sort_order))
    db.commit()
    return {"success": True, "id": db.execute("SELECT last_insert_rowid()").fetchone()[0]}

@router.put("/{cid}")
def update(cid: int, body: BCBody, _: dict = Depends(verify_admin_user)):
    db = get_db()
    db.execute("UPDATE brand_category SET name=?,product_type=?,sort_order=? WHERE id=?",
               (body.name, body.product_type, body.sort_order, cid))
    db.commit()
    return {"success": True}

@router.delete("/{cid}")
def delete(cid: int, _: dict = Depends(verify_admin_user)):
    db = get_db()
    db.execute("DELETE FROM brand_category WHERE id=?", (cid,))
    db.commit()
    return {"success": True}
