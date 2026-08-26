# 凌瑶智数 · 多租户 SaaS 主框架

> 面向医药企业的垂直化软件服务平台 · 多租户架构 · 三层权限 · 子任务接入 · 私有化部署

## 🎯 项目定位

**凌瑶智数 LingYao Digital** — 面向医药企业的垂直化软件服务商

- **4 大产品线**：
  - 🎯 **GEO 智策** — AI 品牌监测与内容优化
  - 🏥 **医院潜力预测** — 医院销售潜力模型预测
  - 💊 **AIDD 研发反馈** — AI 辅助药物研发
  - 🤖 **药企协作辅助智能体** — 让药企内部效率提升 300%

## 🏗️ 技术栈

| 层 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 主框架 | Java + Spring Boot | Java 21 + Spring Boot 3.3.5 | 与 GS-CoLab 决策一致 |
| 数据访问 | Spring Data JPA + Hibernate | 6.5.3 | |
| 数据库（开发） | H2 内存 | 2.x | 重启即清空 |
| 数据库（生产） | PostgreSQL | 16+ | schema 隔离 |
| 认证 | JWT (HS512) | jjwt 0.12.6 | 含 cid + product_code + role_code |
| 推送 | OkHttp | 4.12 | 飞书/企微/微信公众号 |
| 前端 | HTML + CSS + JavaScript | 原生 | 保持炫酷视觉 |
| 部署 | Docker Compose | 待 Phase 6 | SaaS + 私有化双模式 |

## 🔐 三层权限模型

```
┌──────────────────────────────────────────────────────────────┐
│ Layer 1 · 租户层（Company）                                   │
│   · company.status = ACTIVE / SUSPENDED                      │
│   · company_product 决定公司购买了哪些产品（前端哪些高亮）   │
│   · 数据 100% 隔离（业务表带 company_id）                     │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌──────────────────────────────────────────────────────────────┐
│ Layer 2 · 用户层（User within Company）                       │
│   · product_user_grant 决定用户被授权哪些产品                │
│   · 同一公司不同用户可见产品范围不同                          │
└──────────────────────────────────────────────────────────────┘
                          ↓
┌──────────────────────────────────────────────────────────────┐
│ Layer 3 · 产品内角色层（User Role within Product）            │
│   · product_user_role 决定用户在产品内的具体角色              │
│   · 例：GEO 内的 super_admin / senior_operator / operator    │
│   · 角色绑定权限列表（permissions JSON）                      │
└──────────────────────────────────────────────────────────────┘
```

**演示场景（您说的）**：

| 账号 | 公司 | 公司购买 | 用户授权 | 看到的产品 |
|---|---|---|---|---|
| `admin` | 全部 | 全部 | 全部 | 4 个全高亮（SUPER_ADMIN） |
| `a_admin` | A 药企 | GEO + HPD | GEO + HPD | GEO/HPD 高亮，AIDD/POR 灰色 |
| `a_user1` | A 药企 | GEO + HPD | 仅 GEO | 仅 GEO 高亮，其余灰色 |
| `b_admin` | B 药企 | AIDD + POR | AIDD + POR | AIDD/POR 高亮，GEO/HPD 灰色 |

## 📊 数据模型（13 张表）

| 表名 | 层级 | 说明 |
|---|---|---|
| `company` | Layer 1 | 租户（公司） |
| `company_user` | Layer 1 | 公司-用户关系 |
| `company_product` | Layer 1 | 公司产品授权（决定高亮/灰色） |
| `product` | 目录 | 产品目录（4 大产品） |
| `product_user_grant` | Layer 2 | 用户产品授权（二次过滤） |
| `product_role` | Layer 3 | 产品内角色定义 |
| `product_user_role` | Layer 3 | 用户在产品内的角色 |
| `sys_user` | 全局 | 系统用户 |
| `registration` | 业务 | 报名/试用意向 |
| `notification_channel` | 业务 | 推送通道配置（飞书/企微/微信） |
| `sub_task` | 业务 | 子任务接入点（4 个产品子任务） |
| `company_audit_log` | 审计 | 审计日志（OEG 决策沿用） |
| `company_invitation` | 业务 | 邀请链接（OEG 决策沿用） |

## 🚀 快速启动

### 启动后端（端口 9091）

```bash
cd /Users/hua/Documents/myself/凌瑶/backend
export JAVA_HOME=/Users/hua/sdk/jdk-21/Contents/Home
mvn org.springframework.boot:spring-boot-maven-plugin:run \
  -Dspring-boot.run.arguments="--server.port=9091 --spring.profiles.active=dev"
```

启动日志显示 `凌瑶智数 LingYao Platform v1.0.0 已启动` 即成功。

### 启动前端（端口 8765）

```bash
cd /Users/hua/Documents/myself/凌瑶/website
python3 -m http.server 8765
```

### 访问地址

| 地址 | 用途 |
|---|---|
| http://localhost:8765/ | 凌瑶智数官网首页 |
| http://localhost:9091/admin/ | 超管后台 |
| http://localhost:9091/h2-console/ | H2 数据库控制台（dev） |
| http://localhost:9091/api/health | 后端健康检查 |

### 演示账号

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin` | `admin123` | 平台超管（全权限） |
| `a_admin` | `pass123` | A 药企管理员（GEO + HPD 超管） |
| `a_user1` | `pass123` | A 药企用户（仅 GEO 操作员） |
| `b_admin` | `pass123` | B 药企管理员（AIDD + POR 超管） |

## 📡 核心 API

### 认证

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "a_admin",
  "password": "pass123",
  "targetProduct": "GEO"   // 可选，登录后跳转产品
}
```

返回：
```json
{
  "code": 0,
  "data": {
    "token": "eyJ...",
    "user": {"id": 2, "username": "a_admin", "isPlatformAdmin": false},
    "company": {"id": 1, "code": "A-DEMO", "name": "A 药企"},
    "products": [
      {"code": "GEO", "granted": true, "licensed": true, "roleCode": "SUPER_ADMIN", "entryPath": "/subtask/geo"},
      {"code": "HPD", "granted": true, "licensed": true, "roleCode": "SUPER_ADMIN", "entryPath": "/subtask/hpd"},
      {"code": "AIDD", "granted": false, "licensed": false, "entryPath": "/subtask/aidd"},
      {"code": "POR", "granted": false, "licensed": false, "entryPath": "/subtask/por"}
    ]
  }
}
```

### 报名（公开）

```http
POST /api/registrations
Content-Type: application/json

{
  "name": "张三",
  "company": "某药企",
  "phone": "13800138000",
  "email": "zhang@test.com",
  "interestedProducts": ["GEO", "HPD"],
  "message": "想了解 GEO 智策"
}
```

返回后自动触发推送（飞书/企微/微信），推送通道需在管理后台激活。

### 管理端（需 JWT）

```http
GET /api/registrations?page=0&size=20&status=PENDING
GET /api/registrations/stats
PATCH /api/registrations/{id}/status?status=CONTACTED
GET /api/admin/dashboard
GET /api/admin/companies
GET /api/admin/channels
PATCH /api/admin/channels/{id}/status?status=ACTIVE
GET /api/admin/subtasks
```

## 🧩 子任务接入规范（待您分派）

主人要建的 4 个子任务，按以下规范接入：

| 子任务 | 入口路径 | API 前缀 | 状态 |
|---|---|---|---|
| **GEO 监测子任务** | `/subtask/geo` | `/api/sub/geo/*` | REGISTERED |
| **医院潜力预测子任务** | `/subtask/hpd` | `/api/sub/hpd/*` | REGISTERED |
| **AIDD 研发子任务** | `/subtask/aidd` | `/api/sub/aidd/*` | REGISTERED |
| **协作智能体子任务** | `/subtask/por` | `/api/sub/por/*` | REGISTERED |

**子任务接入清单（我会告诉每个子任务的）**：
1. 你在大框架的"产品子任务"位置（你的子任务 code 是 `geo-monitor` / `hpd-predictor` / `aidd-engine` / `por-agent`）
2. 你的入口路径（前端菜单跳转用）
3. 你的 API 前缀（主框架代理/转发用）
4. 鉴权方式：主框架透传 JWT（`Authorization: Bearer xxx`），包含 cid + pcode + rcode
5. Webhook 回调地址（子任务完成后通知主框架）
6. 数据隔离：你只能看到 company_id = 当前用户的 cid 的数据
7. 健康检查：主框架会 ping 你的 `health_url` 确认状态

**子任务技术栈**：主人决定（任意语言，推荐 Python FastAPI 或 Java Spring Boot）

## 📅 实施进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| **Phase 1** | Java 主框架 + 13 张表 + 用户名密码登录 | ✅ 已完成 |
| **Phase 2** | 报名管理 + 推送（飞书/企微/微信） | ✅ 已完成 |
| **Phase 3** | 超管后台 HTML UI | ✅ 已完成 |
| **Phase 4** | 三层权限完整实现 + API 强制校验 | ✅ 已完成 |
| **Phase 5** | 前端产品列表按授权过滤 | ⏳ 待启动 |
| **Phase 6** | 子任务接入框架（4 份规范文档） | ⏳ 待启动 |
| **Phase 7** | 私有化 Docker Compose + 升级脚本 | ⏳ 待启动 |

## 🎁 您已获得的资产

- ✅ Java 17+ Spring Boot 3 主框架工程（Maven 单模块）
- ✅ 13 张数据表 + 完整 JPA 实体
- ✅ 用户名密码登录 + JWT 鉴权
- ✅ 三层权限模型（已端到端测试通过）
- ✅ 报名管理 API + 异步推送
- ✅ 飞书/企微/微信公众号 3 通道推送（已实现，待主人配置 Webhook）
- ✅ 超管后台 HTML（管理公司/报名/通道/子任务）
- ✅ H2 内存数据库（开发期零配置）
- ✅ PostgreSQL 配置（生产期一键切换）

## 🔗 相关文件

- 后端工程：`/Users/hua/Documents/myself/凌瑶/backend/`
- 前端展示：`/Users/hua/Documents/myself/凌瑶/website/`
- OEG 项目（参考）：`/Users/hua/Documents/myself/OEG/geo-platform/`
- 设计决策记忆：`/Users/hua/.workbuddy/MEMORY.md`

---

*凌瑶智数 · 让医药企业从经验判断升级为数据智能精准决策*
