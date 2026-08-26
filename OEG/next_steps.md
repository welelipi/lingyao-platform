# OEG 任务 · 推荐下一步

> **本文件是候选清单，不是指令。**
> 新窗口接管后第一件事 = 问主人想推哪条线。
> 主人拍板后，把对应小节标记为「进行中」并写新一天的 memory。

## 候选 A：AIDD 后端 JWT 改造（P0）

**目标**：让凌瑶签发的 token 真正进入 AIDD 后端（当前只 UI 跳板）。

**改动**：
- `AIDD/ai-project-copilot/backend/src/main/java/com/aidd/copilot/security/JwtAuthInterceptor.java`（新建）
- `WebMvcConfig.java` 注册拦截器
- `application.yml` 共享 `jwt.secret` 路径或环境变量
- 与凌瑶 portal / GEO 后端用同一 secret

**风险**：
- Maven 编译可能拖时间
- AIDD 现有多租户拦截器可能与 JWT 拦截器冲突，要排优先级

**预期工期**：1-2 个完整会话。

## 候选 B：审计日志 + Platform-Hub dashboard 前端（P1）

**目标**：把 4 条审计日志和 dashboard 5 个 API 接到前端。

**改动**：
- 新建 `geo-frontend/src/pages/AuditLog.vue`（含时间/操作者/公司/类型/详情）
- 新建 `geo-frontend/src/pages/PlatformHub.vue`（dashboard 5 卡片 + 公司列表 + 用户列表 + 审计入口 + 许可证占位）
- `router/index.js` 加路由
- 接入 `api/index.js` 的 5 个 Platform-Hub API

**风险**：
- AntD Table 列定义较细
- 审计日志可能很多（159 条 + 4 条）要分页

**预期工期**：1 个完整会话。

## 候选 C：HTTPS / 数据库备份 / 升级脚本（P0）

**目标**：让私有化客户拿到完整生产化方案。

**改动**：
- `install-saas.sh` 增加 certbot 自动签发 + Nginx 反代
- 新增 `backup.sh` + cron 配置
- 新增 `upgrade.sh` + schema migration 版本号机制
- 更新 `SAAS-DEPLOY.md`

**风险**：
- macOS/Linux 路径差异
- certbot 申请需 80 端口，私有化客户网络情况各异

**预期工期**：2 个完整会话。

## 候选 D：CACS / 协作智能体模块占位骨架（P1）

**目标**：让凌瑶 portal 的 4 个模块卡都有「可点击」入口（即便只是占位页）。

**改动**：
- `凌瑶/products/cacs/README.md`（项目骨架）
- `凌瑶/products/cacs-backend-py/main.py`（占位 FastAPI 返 200）
- `凌瑶/products/cacs-frontend/index.html`（占位"建设中"页）
- `凌瑶/products/collab/` 同上
- 凌瑶 portal MODULES 列表把 status=coming_soon 改为 status=skeleton（可点击但有提示）

**风险**：
- 占位要做对，否则用户期待过满

**预期工期**：1 个会话。

## 候选 E：GEOadmin 密码改回强密码（P0）

**目标**：生产前必须改，避免主人接手时被旧明文坑。

**改动**：
- 单次 Python 脚本：bcrypt 哈希新密码 → UPDATE sys_user SET password=? WHERE username='GEOadmin'
- 把新密码写到 `凌瑶/OEG/CHANGELOG_HANDOFF.md`（用 1Password 等保管；本文件仅记录"已重置"事实）
- 同步更新 `PROJECT_STATE.json`（移除明文字段）

**风险**：
- 主人记不住新密码时，要提供一次"重置"机制

**预期工期**：5 分钟。

## 推荐顺序

按主人 14:04 的"继续往前推进 OEG"的语气，建议：

1. **第一刀**：候选 E（5 分钟，立刻止血）。
2. **第二刀**：候选 A（AIDD 后端 JWT，闭环 SSO 故事）。
3. **第三刀**：候选 B（前端 UI 收尾）。
4. **第四刀**：候选 C（生产化打包）。
5. **第五刀**：候选 D（新模块骨架）。

但这是 AI 的建议，**主人拍板为准**。

---

_本文件由 2026-08-11 14:04 旧窗口生成。_
