# 凌瑶智数 · 端口变更 SOP（Port Change Checklist · V1.0）

> **owner**: 主站窗口 · **reviewer**: 主人 · **创建**: 2026-09-13
> **强制**: 任何端口变更前**必须**按本 checklist 逐项走完；走不完 = 服务会断

---

## §1 触发场景（什么时候走本 SOP）

| 场景 | 紧急度 | 适用范围 |
|---|---|---|
| 加新端口（新子平台/新服务） | 低 | dev/staging/prod 全栈 |
| 改现有端口（X → Y） | **高** | 全栈 + OAuth 厂商 |
| 环境端口差异化（dev=9091 → staging=9092） | 中 | 仅新增环境 |
| 端口冲突排查（两个服务争用） | **紧急** | 立即处理 |
| 退役旧端口（如 8765） | 中 | 全栈清理 |

---

## §2 改端口前必走清单（P0 · 阻塞性）

### Step 1 · 影响面盘点（30 分钟）
- [ ] 列出所有引用该端口的代码位置（grep 全仓）
- [ ] 列出所有引用该端口的配置文件（yml/json/.env）
- [ ] 列出所有引用该端口的部署文档（nginx.conf / docker-compose / systemd unit）
- [ ] 列出所有引用该端口的厂商后台（飞书/钉钉/企微 OAuth 注册）
- [ ] 列出所有引用该端口的监控告警（Grafana / Prometheus）

### Step 2 · 主人拍板（必做 · 不可跳过）
- [ ] 写一份 1 页 PRD 描述变更理由 + 影响面 + 回滚方案
- [ ] 主人审批通过
- [ ] 拍板窗口期（推荐避开工作日 9:00-21:00 业务高峰）

### Step 3 · 备份当前状态
- [ ] 备份 `infra/port-allocation.md` 当前版本（git commit tag）
- [ ] 备份所有相关 yml/.env 配置
- [ ] 备份 Nginx 反代配置（`/etc/nginx/conf.d/lingyao.conf`）
- [ ] 备份 OAuth 厂商注册截图（飞书/钉钉/企微后台）

---

## §3 改端口实施清单（按顺序）

### 3.1 代码层（按产品仓）
- [ ] **主站 backend**：修改 `application*.yml` 中 `server.port`
- [ ] **主站 frontend**：修改 `js/script.js` 中 `API_BASE` 解析逻辑
- [ ] **GEOM backend**：修改启动脚本 `--port` 参数
- [ ] **GEOM frontend**：修改 `vite.config.js` 中 port + `package.json` scripts
- [ ] **其他子平台**：按相同模式
- [ ] **同步三个拷贝**：dev/staging/prod 配置同步更新

### 3.2 配置层
- [ ] **CORS 白名单**：SecurityConfig.java 中 `allowedOrigins` 列表更新
- [ ] **JWT claims 配置**：检查是否依赖端口（一般不依赖，但需确认）
- [ ] **SSO redirect_uri**：SSO 配置文件中的回调 URL 端口更新
- [ ] **Nginx 反代**：`proxy_pass http://127.0.0.1:9091` → 9092 等
- [ ] **systemd unit**：`Environment=SERVER_PORT=9091` 更新
- [ ] **docker-compose**：`ports: - "9091:9091"` 更新

### 3.3 厂商注册（**最危险 · 走本 SOP 核心**）
- [ ] **飞书开放平台**：`https://open.feishu.cn/app/{app_id}/security` → 重定向 URL 列表
  - 修改后需要「审核通过」才能生效（**0.5~3 天**）
  - 旧 URI 立即失效（如果删了）
- [ ] **钉钉开放平台**：`https://open-dev.dingtalk.com/developer` → 应用信息 → OAuth 2.0
  - 修改后立即生效（无需审核）
- [ ] **企业微信管理后台**：`https://work.weixin.qq.com/wework_admin/...` → 微信授权
  - 修改后需要「企业微信审核」（**1~3 天**）
- [ ] 详细清单见 `infra/oauth-redirect-uri-registry.md`

### 3.4 文档层
- [ ] 更新 `infra/port-allocation.md` §2/§3 表格
- [ ] 更新 `infra/oauth-redirect-uri-registry.md` 厂商注册时间戳
- [ ] 更新各产品 CHANGELOG.md 标注 `[port-change]`
- [ ] 更新 README.md / docs/ 中的端口引用

---

## §4 部署清单（按顺序）

### 4.1 dev 环境先发（验证性）
- [ ] 重启 dev 服务（daemon / launchd）
- [ ] 浏览器实测核心流程：登录 / SSO / 数据查询 / 跨服务调用
- [ ] 跑回归测试套件（V2.0.77.x r5 测试套件）
- [ ] 观察 24 小时监控（无 4xx/5xx 异常）

### 4.2 staging 灰度
- [ ] 部署 staging 服务
- [ ] 通知相关人员（销售 / 客服 / 试用客户）切到 staging 验证
- [ ] staging 跑通至少 72 小时无异常
- [ ] 主人验收通过

### 4.3 prod 切换（窗口期：建议凌晨 2:00-5:00）
- [ ] 提前 24 小时公告（用户 / 客服 / 销售）
- [ ] **先发 OAuth 厂商**：等飞书/企微审核通过
- [ ] **DNS 切流**：提前 24h 把 TTL 改到 5min
- [ ] **滚动发布**：Nginx 反代先切到新端口（验证 5min）
- [ ] **老端口保留 24h**（兜底回滚）：同时监听 9091 + 9092
- [ ] **24h 后撤老端口**

### 4.4 回滚预案
- [ ] 保留旧端口监听 24h（双端口并存）
- [ ] Nginx 反代一键切回（提前写好 `rollback.sh`）
- [ ] 数据库 / OAuth / JWT secret 等不需要回滚的资产不动
- [ ] 如果是 OAuth 厂商审核不通过，必须延后切换直到通过

---

## §5 验证清单（部署后必跑）

### 5.1 主站自身
- [ ] `curl http://127.0.0.1:9091/portal.html` → 200
- [ ] `curl http://127.0.0.1:9091/api/auth/captcha` → 200
- [ ] D-3 E2E：`/tmp/d3_e2e.py` → licensePlan + geomPlan 命中
- [ ] liluling99 E2E：`/tmp/liluling99_e2e.py` → NVS 公司显示

### 5.2 子平台
- [ ] `curl http://127.0.0.1:8090/` → 200
- [ ] `curl http://127.0.0.1:5180/` → 200
- [ ] GEOM `/api/auth/me` 返回正确 plan

### 5.3 跨服务
- [ ] 主站 SSO → GEOM 回调 → 落地
- [ ] 主站 SSO → MPDM 回调 → 落地
- [ ] 各 SSO callback URL 都能被外部网络访问（飞书后台测试）

### 5.4 OAuth 厂商
- [ ] 飞书登录：扫码 → 回调 → 落地
- [ ] 钉钉登录：扫码 → 回调 → 落地
- [ ] 企微登录：扫码 → 回调 → 落地
- [ ] OIDC 通用：授权 → 回调 → 落地

### 5.5 监控告警
- [ ] 端口未监听 → 立即告警
- [ ] 5xx 错误率 > 1% → 告警
- [ ] OAuth 登录失败率 > 5% → 告警

---

## §6 常见踩坑（P0 教训 · 来自历史 CHANGELOG）

### 踩坑 1: 飞书 redirect_uri 改了但厂商审核没通过 → 用户 3 天无法登录
**教训**: 改 OAuth URI 必须**先提交厂商审核 → 等通过 → 再切 DNS**。顺序不能反。

### 踩坑 2: portal.html `API_BASE` 写死 9091，但 Nginx 配的是 9092 → 前端 fetch 全部 404
**教训**: 前端 API_BASE 必须从 Nginx 反代层拿（用相对路径），不要写死。

### 踩坑 3: V2.0.32 admin.html fetch 没带 `credentials:'include'` → 跨源不携带 cookie → CsrfFilter 403
**教训**: fetch 必须带 `credentials:'include'` + `X-XSRF-TOKEN` header（Double-Submit 模式）。

### 踩坑 4: GEOM dev:prod 5182 配 8092，但 8092 没起 → strictPort 占位失败 → vite 起不来
**教训**: 前端 dev 端口 + 后端端口必须同时起；要么都起，要么都不起。

### 踩坑 5: dev 改 9091 → staging 9092 → prod 9091 → 不同步更新 `.env` → 本地连错环境
**教训**: `.env` 单一真源，禁止散落硬编码。

---

## §7 不在本 SOP 范围

- ❌ 域名变更（DNS 切换）：走 `infra/dns-change-sop.md`（待写）
- ❌ SSL 证书更新：走运维 SOP（不在主站窗口范围）
- ❌ 服务器迁移：走运维 SOP
- ❌ 子平台内部端口调整：各子平台窗口自己走，但需通知主站更新本表

---

## §8 SOP 版本

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.0 | 2026-09-13 | 初版（基于主人 10:22 端口稳定性关切） |

---

*maintained by: 主站窗口 · 联系: 主人 拍板 · last reviewed: 2026-09-13*
