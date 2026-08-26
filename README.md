# 凌瑶智数 — 主项目

> **凌瑶智数是 4 个产品模块的 SaaS 总入口。**
> 用户在凌瑶登录后选择模块，进入对应产品。
> 所有产品共享同一套用户体系、SSO、视觉规范。

---

## 产品矩阵（4 个模块）

| # | 模块 | 状态 | 代码位置 | 端口 |
|---|---|---|---|---|
| 1 | **GEO 智策**（品牌优化）| ✅ 已接入 | `products/geo-platform` | 9180 (前端) / 9090 (后端) |
| 2 | **AIDD 系统**（研发反馈）| ✅ 已接入 | `products/aidd` | (待定) |
| 3 | **医院潜力预测**（CACS）| 🚧 规划中 | （未启动） | — |
| 4 | **协作智能体**（项目管理）| 🚧 规划中 | （未启动） | — |

## WorkBuddy 层级

```
凌瑶智数（WorkBuddy 主 Workspace）
├── 📦 主项目（本目录）
│   ├── website/        # 灵瑶智数官网 + 登录门户 + 模块选择
│   ├── shared/         # 共享代码（用户中心 / SSO / JWT secret）
│   └── products/       # 产品矩阵（符号链接）
│       ├── geo-platform → /Users/hua/Documents/myself/OEG/geo-platform
│       └── aidd        → /Users/hua/Documents/myself/AIDD/ai-project-copilot
└── 🎯 任务（WorkBuddy Task 概念）
    ├── Task: GEO 智策运营迭代
    ├── Task: AIDD 系统集成
    ├── Task: 凌瑶登录门户搭建
    └── Task: 统一用户中心建设
```

## SSO 协议

凌瑶登录后，跳转到具体产品时携带：

```
{product_url}/?token={JWT}&from=lingyao&company_id={N}&redirect={path}
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `token` | ✅ | 凌瑶签发的 JWT（含 `cid` / `uid` / `role` / `cats`） |
| `from` | ✅ | 固定 `lingyao`，标识来源 |
| `company_id` | ⭕ | 当前公司 ID（多租户隔离） |
| `redirect` | ⭕ | 登录后跳到具体页面，默认 `/action-center` |

## 视觉规范（所有产品统一）

来源：127.0.0.1:8765 (凌瑶 website)

- 主背景：`#050F22` 深海军蓝
- 品牌色：`#00D4FF` 青色
- 辅色：`#7B61FF` 紫色 / `#FF8C42` 橙 / `#4ADE80` 绿 / `#FF5E94` 粉
- 字体：Inter + Noto Sans SC
- 圆角：`14px`
- 玻璃态：`rgba(10, 31, 61, 0.55)` + `backdrop-filter: blur(24px)`
- 渐变：`linear-gradient(135deg, #00D4FF, #7B61FF)`

## 当前进度（截至 2026-08-11）

- [x] Phase 11.1 物理重整 — 凌瑶成为主 workspace
- [x] Phase 11.2 凌瑶升级为真正登录门户
- [x] Phase 11.3 统一用户中心（逻辑共享 GEO 数据库与 JWT secret）
- [x] Phase 11.4 GEO 接受 SSO（`?token=&from=lingyao&company_id=`）
- [x] Phase 11.5 AIDD 前端接受 SSO（后端 JWT 仍待补齐）
- [x] Phase 11.6 凌瑶 → GEO / AIDD 端到端验证

> 交接入口：`OEG/START_HERE.md`。新窗口应先阅读该文件，再阅读 `OEG/HANDOFF.md`。

---

_2026-08-11 Phase 11 起，凌瑶正式成为主项目。_