# OEG 任务 · 接管指南

> **本目录是凌瑶智数下"OEG"任务的载体。**
> 新窗口（在新 WorkBuddy 工作区 OEG 内）打开后，**先读本文档** → 再读 `HANDOFF.md` → 然后按"接管动作清单"逐条执行。

## 1. 你是谁、我为什么在这里

- **你**：花卷 — WorkBuddy AI 助手，凌瑶智数下的 OEG 任务执行者。
- **我（移交人）**：上一会话的花卷，已完成 OEG 项目的 SaaS 化（Phase 1–6）、凌瑶门户化（Phase 11）、GEO 视觉重塑（Phase 10）等 6 大阶段共 50 个子任务。
- **主人原话**："我会废弃掉这个我们现在这个对话窗的出入口，然后我会新建一个呃新建一个任务，把这个任务放在凌瑶的这个项目的文件夹内，然后命名为 OEG，然后继续往前推进。我需要你把你现在所有的记忆，所有的功能工作的这个进度全都打包记录好，然后我告诉另外一个新窗口的时候，让他直接接过来就可以了。"
- **你的目标**：在 `OEG/` 工作区内继续推进 OEG / GEO / 多租户 SaaS / 凌瑶智数门户相关工作，无需回头翻历史会话。

## 2. 一句话项目定义

> **OEG = 金赛药业 GEO 品牌智能体 SaaS 平台。**
> 凌瑶智数（`/Users/hua/Documents/myself/凌瑶`）= SaaS 总入口；OEG（含 GEO 智策产品）= 凌瑶下的一个任务；AIDD 系统 = 凌瑶下的另一个任务。3 个产品共享同一套用户体系、JWT secret、视觉规范、SSO 协议。

## 3. 必读文档（按顺序）

1. `OEG/START_HERE.md` ← 你正在看。
2. `OEG/HANDOFF.md` ← **详细交接**：架构图、决策清单、文件清单、运行状态、待办、风险。
3. `OEG/PROJECT_STATE.json` ← **机器可读快照**：服务端口、PID、SQLite 行数、文件清单（供新窗口一次性加载）。
4. `OEG/handoff/CONTEXT_BRIEF.md` ← 短版 briefing，可在 90 秒内掌握全局。
5. `OEG/handoff/CONTEXT_DUMP.md` ← **完整上下文转储**（含历史决策、Phase 1–11 详细日志、未完成清单、需确认事项）。
6. `OEG/handoff/RUN_COMMANDS.md` ← 启动 / 停止 / 验证命令清单。
7. `OEG/handoff/CHANGELOG_HANDOFF.md` ← 移交日志（谁、在何时、移交了什么）。

## 4. 接管动作清单（新窗口必做）

按顺序执行 7 步，每步完成后在 `OEG/handoff/CHANGELOG_HANDOFF.md` 记录一句"Step X done"。

- [ ] **Step 1** — 阅读 `HANDOFF.md` 至少前 4 章（架构 / 当前服务 / 已完成 / 未完成）。
- [ ] **Step 2** — 读取 `PROJECT_STATE.json`，把所有路径/端口/PID 与本机对齐（重点校验 4 个端口是否真在 LISTEN）。
- [ ] **Step 3** — 阅读 `CONTEXT_DUMP.md` 关键章节（12 项 SaaS 决策、Phase 10 视觉规范、Phase 11 凌瑶门户化）。
- [ ] **Step 4** — 询问主人下一步（"继续做 P0 哪一项？AIDD 后端 JWT？还是 Phase 12？"），把回答记入 `CHANGELOG_HANDOFF.md`。
- [ ] **Step 5** — 校验 Git 改动（`git -C /Users/hua/Documents/myself/凌瑶/products/geo-platform status --short`），确认自己改动的范围。
- [ ] **Step 6** — 启动一次端到端冒烟（参见 `RUN_COMMANDS.md` 的"端到端冒烟脚本"小节），确保 4 个端口正常。
- [ ] **Step 7** — 一切就绪后向主人报告："OEG 接管完成，当前可推进 X / Y / Z。"

## 5. WorkBuddy 工作区约束

- **当前工作区**：`/Users/hua/Documents/myself/OEG`（仍然存在，主人尚未切换到凌瑶）。
- **目标工作区**：`/Users/hua/Documents/myself/凌瑶`（主项目）。
- **过渡**：
  - 主人会在 WorkBuddy UI 左侧"工作空间"下拉里，把当前会话切到"凌瑶" → 选 `OEG/` 子目录作为任务路径。
  - 你在新窗口里**只**在 `/Users/hua/Documents/myself/凌瑶/` 下工作（含其 `OEG/`、`products/geo-platform/` 等符号链接），不要再回 `OEG/` 原路径。
  - **不要删任何原 `OEG/` 目录**——它是 `凌瑶/products/geo-platform` 的真实源数据。

## 6. 铁律（绝对不要违反）

1. **GEO 业务代码零触碰**：本会话只在数据访问层做透明 SaaS 化，业务 router 一行未改（见 `HANDOFF.md` §4.3）。
2. **统一用户中心共享 JWT secret**：凌瑶 portal → GEO 后端的 token 之所以能通用，是因为两者共用 `OEG/geo-platform/geo-backend-py/data/.jwt-secret`。
3. **多租户隔离铁律**：所有 GEO 业务接口必须经过 `db_patch.py` 的 SQL 改写；新写的接口必须用 `Depends(get_current_user)`。
4. **任何破坏性动作前必须主人确认**（删表、删库、reset 密码、删 workspace 等）。
5. **新窗口写新 memory**：在 `凌瑶/.workbuddy/memory/YYYY-MM-DD.md` 继续累加；不要在原 OEG memory 上续写（OEG memory 已被同步过来一次，作只读快照即可）。

## 7. 我可以问什么、不能问什么

- ✅ 可以问：当前主人最想推进哪条线？Phase 12 的优先级？AIDD 后端是否要 JWT 改造？门户页 UI 调整？
- ❌ 不要问：与本次工作无关的话题（投资、行情、其他项目）。

---

_本文件由 2026-08-11 14:04 旧窗口生成；新窗口首次进入时阅读即弃。_
