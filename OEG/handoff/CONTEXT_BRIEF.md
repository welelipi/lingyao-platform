# CONTEXT_BRIEF.md — 90 秒掌握全局

> 给新窗口的"电梯演讲"。

## 1. 30 秒项目定位

**OEG** = 金赛药业 **GEO 品牌智能体 SaaS 平台**，物理路径 `/Users/hua/Documents/myself/凌瑶/_external/OEG/geo-platform/`（2026-08-11 实体重定位后），凌瑶访问入口 `/Users/hua/Documents/myself/凌瑶/products/geo-platform`（符号链接）。

它已经完成"**多租户 SaaS 化 + 凌瑶智数门户化 + 视觉规范统一**"三件大事：

1. **多租户 SaaS 化**：所有业务表加 `company_id`；JWT 携带 `cid`；SQL 改写层透明拦截。
2. **凌瑶智数门户化**：凌瑶 = 主 workspace（127.0.0.1:8765），OEG = 子任务；统一登录 + 4 模块门户 + 跨产品 SSO。
3. **视觉规范统一**：GEO 前端从浅色 AntD 改为深色青色（灵瑶品牌），CSS tokens 已沉淀。

## 2. 30 秒架构

```
凌瑶智数（主项目，127.0.0.1:8765）
├── portal 后端 (FastAPI :8765)        ← 登录 + 4 模块门户 + 签发 JWT
├── website/portal.html                 ← 4 模块选择页
└── products/
    ├── geo-platform → OEG/geo-platform ← GEO 智策
    │   ├── 后端 (FastAPI :9090)
    │   └── 前端 (Vite :9180)
    └── aidd → AIDD/ai-project-copilot   ← AIDD 系统
        └── 前端 (静态 :9191，SSO 跳过登录)
```

- **统一用户中心**：凌瑶 portal 直接读 GEO 的 `sys_user` 表 + 共享 JWT secret。
- **SSO 协议**：`{url}/?token=&from=lingyao&company_id=&redirect=`。

## 3. 30 秒服务状态（必须立刻校验）

| 服务 | 端口 | 验证命令 |
|---|---|---|
| 凌瑶 portal | 8765 | `curl --noproxy '*' http://127.0.0.1:8765/api/health` |
| GEO 后端 | 9090 | `curl --noproxy '*' http://127.0.0.1:9090/docs` |
| GEO 前端 | 9180 | `curl --noproxy '*' http://localhost:9180/` |
| AIDD 前端 | 9191 | `curl --noproxy '*' http://127.0.0.1:9191/login.html` |

如果端口不在 LISTEN，看 `RUN_COMMANDS.md` §"服务启动"。

## 4. 登录信息

- **凌瑶 portal**：`http://127.0.0.1:8765/portal` → 用户名 `GEOadmin` 密码 `lingyao@2026`。
- **GEO 后端 dev-token**：见 `PROJECT_STATE.json` 里的 `geo_dev_token_T1` / `T99`。

## 5. 主人接下来的可能方向

新窗口要做的第一件事：**问主人想推进 Phase 12 的哪条线**。候选：

- AIDD 后端 JWT 改造（写 Java 拦截器）
- HTTPS / 数据库备份 / 升级脚本（生产化 P0）
- 审计日志 + Platform-Hub dashboard 前端（补 UI）
- CACS / 协作智能体模块占位骨架
- 凌瑶 portal 视觉微调 / 4 模块状态切换
- GEOadmin 密码改回强密码

## 6. 别踩的红线

1. **GEO 业务代码零触碰**。
2. **凌瑶 portal ↔ GEO 后端共享 JWT secret**（不能改一不改成两个）。
3. **新写的接口必须用 `Depends(get_current_user)`**。
4. **任何破坏性动作前必须主人确认**。

## 7. 文档路径

- `OEG/START_HERE.md` — 第一站
- `OEG/HANDOFF.md` — 详细交接
- `OEG/PROJECT_STATE.json` — 机器可读快照
- `OEG/handoff/CONTEXT_DUMP.md` — 完整上下文（含 12 项 SaaS 决策、Phase 1–11 日志）
- `OEG/handoff/RUN_COMMANDS.md` — 启动 / 停止 / 验证命令

---

_90 秒读完，直接进入工作。_
