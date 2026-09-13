# 凌瑶智数 · 端口分配表（Port Allocation · 单一真源）

> **owner**: 主站窗口 · **reviewer**: 主人 · **创建**: 2026-09-13
> **关联文档**: `infra/port-change-checklist.md` · `infra/oauth-redirect-uri-registry.md`
> **铁律**: **改任何端口前必须更新本表 + 走 checklist · 任何 PR 涉及端口必须同步本表**

---

## §1 总览（7 产品 × 4 环境）

### 环境代号
| 环境 | 用途 | 数据库 | 部署方式 |
|---|---|---|---|
| **dev** | 本地开发 | H2 内存 / lingyao_dev (PG) | daemon 守护 |
| **staging** | 预发布（发布前验证） | lingyao_staging (PG) | systemd / CVM |
| **prod** | 生产（私有化） | lingyao (PG) | systemd + CVM |
| **prod-saas** | 生产（SAAS 公有云） | lingyao (PG) | k8s + ingress |

---

## §2 主站（凌瑶智数）

| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** (Java Spring Boot) | **9091** | **9092** | **9091** | 9091 |
| **前端** (静态 HTML) | 走 9091 static | 走 9092 static | 走 9091 static | 走 9091 static |
| ~~portal-backend-py~~ | ~~8765~~ | ~~✗~~ | ~~✗~~ | ~~✗~~ | **V2.0.77.25 退役**（前端由 9091 jar 内置 static 服务）|

### 历史变更轨迹
| 日期 | 端口变更 | 原因 |
|---|---|---|
| 2026-07-? | 8765 (Python/FastAPI) | 初版 |
| 2026-08-? | 8765 + 9091 双活（dev-pg 模式） | V2.0.53 引入 Java |
| 2026-09-08 | 9091 主路径 + 8765 兼容（V2.0.77.7） | 修复 8765 静态资源 404 |
| **2026-09-13** | **8765 退役（V2.0.77.25）** | **前端由 9091 jar 内置 static 服务，Python http.server 下线** |

---

## §3 子平台端口

### 3.1 GEOM 棱镜-智能 GEO 监测
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** (Python FastAPI) | **8090** | 8091 | 8090 | 8090 |
| **前端** (Vue + Vite) | **5180** | 5181 | 5182 | 5182 |

**注**: GEOM 启动命令三环境：`npm run dev`（5180→8090）/ `npm run dev:staging`（5181→8091）/ `npm run dev:prod`（5182→8092）

### 3.2 MPDM 皓元-智能医院决策中枢
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** | **8100** | TBD | 8100 | 8100 |
| **前端** | **3100** | TBD | 3100 | 3100 |

### 3.3 AIDD 源策-医药 AI 研发
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** | **18080** | TBD | 18080 | 18080 |
| **前端** | **13000** | TBD | 13000 | 13000 |

### 3.4 POR-M 明枢-医药情报门户
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** | **8280** | TBD | 8280 | 8280 |
| **前端** | **3190** | TBD | 3190 | 3190 |

### 3.5 D-Info 辰录-医院管理
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** | **8281** | TBD | 8281 | 8281 |
| **前端** | **5181** | TBD | 5181 | 5181 |

### 3.6 MED-PPT 医呈-MSL 报告生成
| 服务 | dev | staging | prod | prod-saas |
|---|---|---|---|---|
| **后端** | 未启 | TBD | TBD | TBD |
| **前端** | 未启 | TBD | TBD | TBD |

---

## §4 跨服务端口引用（高风险断点）

### 4.1 SSO 跳转链
```
主站 9091 → 飞书 OAuth → 主站 9091 /api/auth/sso/feishu/callback
                                ↓
                          GEOM 8090 /sso/callback
                          MPDM 8100 /sso/callback
                          AIDD 18080 /sso/callback
                          ...
```

### 4.2 前端 API_BASE 反代
| 文件 | 当前写法 | 风险 |
|---|---|---|
| `website/js/script.js` L406-408 | `localhost/127.0.0.1` → 9091，其他 → 相对路径 | 9091 写死 |
| `geom/geo-frontend/vite.config.js` L21 | `port === 5180 ? 8090 : ... 8091 : ... 8092` | 写死映射表 |
| `geom/geo-frontend/.env.development` | `VITE_API_BASE=http://localhost:8090` | 写死 |
| 各子平台 frontend `package.json` scripts | `dev` / `dev:staging` / `dev:prod` 三脚本 | 写死 |

### 4.3 后端 CORS 白名单（hardcode 高危）
```
backend/src/main/java/com/lingyao/platform/security/SecurityConfig.java
allowedOrigins = ["http://localhost:9091", "http://127.0.0.1:9091", ...]
```

### 4.4 OAuth redirect_uri（厂商注册 · 最高风险）
见 `infra/oauth-redirect-uri-registry.md`

---

## §5 端口分配原则（铁律）

### P1：环境隔离
- staging 端口必须 ≠ prod 端口（避免开发误连生产）
- 当前规则：prod=9091 / staging=9092（错开 1 位）

### P2：产品隔离
- 7 个产品后端端口互不冲突（8765/8090/8100/18080/8280/8281/9091）
- 7 个产品前端端口互不冲突（5180/5181/5182/5183/3100/3190/13000）

### P3：dev 模式优先
- dev 端口固定不变（如 9091/8090/5180），便于本地开发跨平台稳定
- staging/prod 才做环境端口差异化

### P4：写代码不写死
- 任何 PR 引入新端口必须同步更新本表
- 任何端口变更必须走 `infra/port-change-checklist.md`

---

## §6 当前待办（不阻塞，治理性工作）

- [ ] 写一个 CI 校验脚本 `infra/check-port-consistency.sh`：grep 检查端口散落
- [ ] portal.html `API_BASE` 改成 `window.location.origin + ':9091'` 环境变量化（V2.0.77.23）
- [ ] GEOM frontend `.env` 加 `VITE_PORT` + `VITE_API_BASE` 自动注入（V0.10.20+）
- [x] CORS 白名单抽到 `application.yml` 配置项（V2.0.77.23 ✅）
- [x] Nginx sub_filter 模板（`infra/nginx-sub-filter-template.conf` · V2.0.77.24 ✅）
- [x] **8765 portal-backend-py 退役公告**（V2.0.77.25 ✅）

---

## §7 验证命令

```bash
# 全仓端口占用快照（V2.0.77.25 起：8765 退役，不再扫描）
for port in 9091 9092 8090 8091 8092 5180 5181 5182 5183 8100 3100 18080 13000 8280 3190 8281; do
  pid=$(lsof -ti :$port 2>/dev/null | head -1)
  [ -n "$pid" ] && echo "$port ↑ PID $pid" || echo "$port ✗"
done
```

---

*maintained by: 主站窗口 · last reviewed: 2026-09-13 · version: V1.0*
