# 凌瑶智数 — 主项目

> **凌瑶智数是 5 个产品模块的 SaaS 总入口。**
> 用户在凌瑶登录后选择模块，进入对应产品。
> 所有产品共享同一套用户体系、SSO、视觉规范。

---

## 产品矩阵（5 个模块）

| # | 模块 | 代码 | 前端端口 | 后端端口 | 代码位置 | 状态 |
|---|---|---|---|---|---|---|
| 1 | **GEO 智策**（品牌优化）| `geo` | 5180 | 8090 | `geom/geo-frontend` + `geom/geo-backend-py` | ✅ V0.9.12.31.3 |
| 2 | **皓元 HPD**（医院潜力预测）| `hpd` | 3100 | 8100 | `../MPD-myself/frontend` + `../MPD-myself/backend` | ✅ V2.0.x |
| 3 | **AIDD 系统**（研发反馈）| `aidd` | 13000 | 18080 | `../AIDD/ai-project-copilot/frontend` + `../AIDD/ai-project-copilot/backend` | ✅ V1.x |
| 4 | **辰录 Dinfo**（数据记录）| `dinfo` | 5181 | 8281 | `../Dinfo/frontend` + `../Dinfo/backend` | ✅ V0.6+ |
| 5 | **明枢 PORM**（项目运营）| `porm` | 3190 | 8280 | `../MPD-myself/frontend`（PORM 子模块）+ `../MPD-myself/backend` | ✅ V1.x |

> **注意**：5 个子产品**不是**凌瑶主仓的子目录，而是 sibling 仓。GEOM 例外，在 `./geom/`（凌瑶主仓的独立副本，但已 gitignore）。
>
> **README 上一版（2026-08-11）的 "products/ 符号链接" 模型已废弃**——4 个子产品已迁移为独立仓，凌瑶主仓不再符号链接。

## 项目结构

```
凌瑶智数（主仓 /Users/hua/Documents/myself/凌瑶）
├── backend/                          # Spring Boot 主后端
│   ├── src/main/java/com/lingyao/platform/
│   │   ├── controller/SubTaskController.java   # 产品路由 + SSO 跳转
│   │   ├── config/LingyaoSubTaskProperties.java  # 配置类（V2.0.11 Fix 1）
│   │   └── ...
│   └── src/main/resources/
│       ├── application.yml          # 含 lingyao.subtask.routes.{geo,hpd,aidd,dinfo,porm}
│       └── data.sql                 # 5 张卡片初始化数据
├── website/                          # 前端静态资源
│   └── portal.html                  # 主控台 + 5 卡片入口
├── geom/                             # GEOM 独立副本（gitignore）
├── deploy/cvm/                       # 腾讯云 CVM 部署文件
│   ├── lingyao-backend.service      # systemd service
│   ├── nginx-lingyao.conf            # nginx 反代
│   ├── backup-h2.sh                  # H2 自动备份
│   └── DEPLOYMENT.md                 # CVM 部署完整文档（V2.0.11 Fix 4）
├── docs/portal-sso-design/           # SSO 协议文档（V2.0.11 Fix 5）
│   ├── 01-main-repo-stability-fixes.md
│   ├── 02-sub-product-sso-adaptation.md
│   └── 03-sso-cors-policy.md
└── scripts/                          # 守护进程启动器
    ├── start_backend_daemon.py      # private profile（prod）
    ├── start_dev_daemon.py          # dev profile（H2 内存库）
    └── start_frontend_daemon.py     # Python http.server :8765
```

## SSO 协议 V1.0（V2.0.11 标准化）

凌瑶登录后，跳转到具体产品时携带 **5 个标准参数**（主仓已统一）：

```
{base_url}/
  ?tenant_id={company_id}            # 租户 ID（多租户隔离）
  &user_id={user_id}                 # 主网站 user_id（子产品用作外键）
  &user={username}                   # URL encoded 用户名
  &display_name={display_name}       # URL encoded 显示名
  &platform_token={JWT}             # 凌瑶签发的 JWT（含 cid/uid/role/claims）
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `tenant_id` | ✅ | 公司 ID，子产品用作 tenant 隔离 |
| `user_id` | ✅ | 主网站 user_id（子产品用作外键关联本地 user 表）|
| `user` | ✅ | 用户名（URL encoded UTF-8）|
| `display_name` | ⭕ | 显示名（URL encoded UTF-8）|
| `platform_token` | ✅ | JWT Bearer token，子产品用 `LINGYAO_JWT_SECRET` 验签 |

**优先级**：配置类 > sub_task 表字段
- `application.yml` 的 `lingyao.subtask.routes.<code>.base-url` 优先生效
- 环境变量 `LINGYAO_SUBTASK_<PRODUCT>_BASE_URL` 进一步覆盖

**共享 JWT Secret**：子产品用 `LINGYAO_JWT_SECRET` 环境变量注入（密钥文件不进 git，部署时生成）。

## 启动方式（开发者）

### 1. 启动后端（dev profile，H2 内存库）

```bash
cd /Users/hua/Documents/myself/凌瑶
python3 scripts/start_dev_daemon.py
# → http://localhost:9091/  · 默认账号 admin/admin123
```

### 2. 启动前端（端口 8765）

```bash
cd /Users/hua/Documents/myself/凌瑶
python3 scripts/start_frontend_daemon.py
# → http://localhost:8765/portal.html
```

### 3. 跨仓启动（主子产品联调）

主仓侧已就位。子产品需要在各自仓里启动：
- **GEOM**：`./geom/geo-frontend/` (5180) + `./geom/geo-backend-py/` (8090)
- **HPD**：`../MPD-myself/frontend/` (3100) + `../MPD-myself/backend/` (8100)
- **AIDD**：`../AIDD/ai-project-copilot/frontend/` (13000) + `../AIDD/ai-project-copilot/backend/` (18080)
- **Dinfo**：`../Dinfo/frontend/` (5181) + `../Dinfo/backend/` (8281)
- **PORM**：`../MPD-myself/frontend/` (3190) + `../MPD-myself/backend/` (8280)

⚠️ **HPD 和 PORM 在同一仓 `../MPD-myself/`，前端用不同端口区分**：
- 3100 = HPD（皓元-智能医院潜力预测）
- 3190 = PORM（明枢-项目运营）

## 视觉规范（所有产品统一）

- 主背景：`#050F22` 深海军蓝
- 品牌色：`#00D4FF` 青色
- 辅色：`#7B61FF` 紫色 / `#FF8C42` 橙 / `#4ADE80` 绿 / `#FF5E94` 粉
- 字体：Inter + Noto Sans SC
- 圆角：`14px`
- 玻璃态：`rgba(10, 31, 61, 0.55)` + `backdrop-filter: blur(24px)`
- 渐变：`linear-gradient(135deg, #00D4FF, #7B61FF)`

## 当前进度（截至 2026-08-29）

- [x] Phase 11.1-11.6 凌瑶主仓初始化（GEO/AIDD SSO）
- [x] V2.0.9-2.0.10 主控台升级 + 公司编辑 + 许可证
- [x] **V2.0.11 主仓稳定性 Fix 1+2+3+4+5**（base_url 配置外移 + daemon 路径参数化 + README + CVM 文档 + SSO-CORS 文档）
- [ ] **跨项目治理**：4 个子产品 SSO 接收端改造（HPD/AIDD/Dinfo/PORM/GEOM，~2.1 人日）

## V2.0.11 稳定性 Fix 清单

| Fix | 内容 | 文件 | 状态 |
|---|---|---|---|
| **Fix 1** | base_url 配置外移（`LingyaoSubTaskProperties.java` + application.yml）| `backend/.../config/LingyaoSubTaskProperties.java` + `SubTaskController.java` | ✅ |
| **Fix 2** | daemon 路径参数化（`LINGYAO_HOME` 环境变量）| `scripts/start_*.py` (3 个) | ✅ |
| **Fix 3** | README 升级（5 产品矩阵 + 5 参数 SSO + 跨仓启动）| 本文件 | ✅ |
| **Fix 4** | CVM 部署文档化（`DEPLOYMENT.md`）| `deploy/cvm/DEPLOYMENT.md` | ✅ |
| **Fix 5** | SSO-CORS 策略文档（主子产品同 `.lingyao.cn` 子域共享 cookie）| `docs/portal-sso-design/03-sso-cors-policy.md` | ✅ |

详细方案文档见 `docs/portal-sso-design/`。

## 跨项目施工

- **HPD SSO 改造**：模板见 `docs/portal-sso-design/02-sub-product-sso-adaptation.md` 第 §1 节（FastAPI `/api/sso/login` + React `main.tsx`）
- **Dinfo SSO 改造**：模板见同文档第 §3 节（Vue Router beforeEach）
- **GEOM SSO 改造**：模板见同文档第 §4 节（不动飞书 callback）
- **AIDD/PORM SSO 改造**：模板见同文档第 §1 节（同 HPD）

---

_2026-08-29 V2.0.11 起，凌瑶主仓"远端代码库就绪度"从 4/10 → 8/10，可直接 push 到 GitHub 供他人 clone。_