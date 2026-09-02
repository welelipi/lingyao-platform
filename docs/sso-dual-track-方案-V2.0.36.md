# 凌瑶主网站 · SSO 双轨制权限体系方案（V2.0.36 主站侧已落地）

> 拍板：主人 2026-09-02 13:02  
> 主站实施窗口：本地主流程窗口（本窗口）  
> 子平台实施：移交 GEOM / MPDM / AIDD / DINFO / POR-M 各自窗口  
> 状态：**主站侧已全部落地并 E2E 通过**；子平台侧待移交



---

## 一、需求原文归纳（主人原话要点）

1. 登录成功后的首页直接透出公司超管功能：上 KPI、中产品列表、下 4 Tab（用户管理/产品授权/SSO配置/集中审批），不再藏按钮
2. 产品展示从卡片改为**列表**（操作是复制链接/进入，不是"打开"）
3. 「管理后台」按钮仅平台超管可见
4. **SSO 双轨**：
   - 公司在主站配置 SSO 后 → 员工用公司 SSO（扫码/飞书客户端内点登录）直接登录子平台
   - 员工在子平台**有权限 → 直接进**；**无权限 → 向公司超管申请**
   - **公司超管不走 SSO**：用主网站发的账号+密码登录子平台，处理审批、调整权限
   - 动机：各子产品适配的部门不同（有的给医学部、有的给市场部），权限特殊性未来要在子平台内调；超管身份必须与普通员工可区分，避免"分不清谁是超管"
   - 公司超管还可以给已登录的公司人员配属子平台专属权限

## 二、双轨身份模型

| 角色   | 登录方式                     | 进入子平台方式                        | 权限调整入口                 |
| ---- | ------------------------ | ------------------------------ | ---------------------- |
| 员工   | 公司 SSO（飞书/钉钉/企微扫码或客户端直点） | 有授权 → 直接进；无授权 → 看到申请页 → 提交后等审批 | 无（只读自己的申请状态）           |
| 公司超管 | **主网站账密**（不走 SSO）        | 子平台登录页保留「账号密码登录」入口，用同一套账密进     | 子平台内调产品内角色 + 主站工作台审批授权 |
| 平台超管 | 主网站账密                    | 全产品直通（现状不变）                    | 主站管理后台                 |

**关键原则**：SSO 只解决"你是谁"（认证），权限仍由主站 `product_user_grant` + 子平台产品内角色决定（授权）。两轨认证产物一致：同一套 JWT claims（`lingyao_user_id` / `tenant_id` / `sub_product_grants`），子平台无需区分 token 来自扫码还是账密。

## 三、主站侧已落地（V2.0.36，E2E 全绿）

### 3.1 首页工作台

- KPI 行：成员数 / License 剩余（<90 天黄色、<30 天红色）/ 已开通产品 / 待审批（红色角标，点击直达审批 Tab）
- 产品列表（行式）：已授权 → [进入] [📋复制链接(超管)]；已申请未批 → 「⏳审批中」；未授权 → [申请开通]
- 4 Tab：用户管理（只读概览+跳完整版）/ 产品授权 / SSO 配置（4 Provider 卡片+配置弹窗）/ ~~集中审批~~ **开通审批**（V2.0.37 更名；通过=自动授权）
- 「公司管理」按钮删除；「管理后台」仅 `isPlatformAdmin` 可见

### 3.2 申请-审批-自动授权闭环（新增后端）

- `POST /api/sub/{code}/apply`：员工申请（校验公司已开通该产品 → 幂等防重 → 写 `sub_task_approval`，type=PRODUCT_GRANT）
- `GET /api/sub/my-approvals`：我的申请状态
- `POST /api/sub/{id}/approve`：**审批通过 → 自动写 `product_user_grant(ACTIVE)`**（REVOKED 复活/新建，幂等）→ 员工下次进入直接放行
- E2E：a_user1 申请 AIDD → admin 审批 → 再 enter 返回 DIRECT_REDIRECT（13000 端口 AIDD 前端）✅

## 四、子平台侧移交项（各子平台窗口实施）

### 4.1 登录页双轨改造（每家子平台）

```
[ SSO 登录区 ]
  ├─ URL 带 ?sso=1 或公司已配 SSO → 显示「飞书扫码登录」按钮（跳主站 /api/sso/feishu/login?company=CODE）
  └─ 公司未配 SSO → 隐藏 SSO 区
[ 账密登录区 ]（常驻，不因 SSO 启用而消失）
  └─ 公司超管专用通道：用主网站账号密码 → POST 主站 /api/auth/login → 拿 JWT → 走既有 sso-callback 进入
```

### 4.2 无权限页申请流（每家子平台）

- 员工 SSO 登录成功但无该产品授权 → 子平台不要展示裸错误，跳转主站 `portal.html`（带提示参数）→ 主站弹「向公司超管申请开通」→ 提交 → 员工看到「⏳审批中」
- 子平台仅需识别 403/`NO_ACCESS` 后**重定向回主站 portal**，申请逻辑全部复用主站（勿在子平台重复建审批表）

### 4.3 超管在子平台内的产品内角色（预留）

- 主站 JWT 已带 `lingyao_user_id`；子平台按 user_id 首次登录自动建号（现状已实现 3 级回退）
- **建议约定**：主站后续在 JWT 增加 `is_tenant_admin` claim（已存在！JwtUtil 生成时写入 `is_tenant_admin`）→ 子平台读此 claim，为超管自动授予子平台内管理员角色（如 GEO 的 admin、HPD 的 manager）
- 各产品部门特殊性（医学部/市场部差异）→ 由公司超管在**子平台内**的产品角色管理页调整，主站只管"能不能进产品"，不管"产品内是什么角色"（Phase 2 解耦原则，不变）

### 4.4 各子平台工作量评估

| 子平台      | 改动                             | 预估 |
| -------- | ------------------------------ | -- |
| GEOM 棱镜  | 登录页加账密入口常驻 + NO_ACCESS 重定向主站   | 小  |
| MPDM 皓元  | 同上                             | 小  |
| AIDD 源策  | 同上（sso-callback.html 已有，补账密分支） | 小  |
| DINFO 辰录 | 同上                             | 小  |
| POR-M 明枢 | 同上                             | 小  |

## 五、安全边界

- 申请接口幂等（同公司同人同产品仅一条 PENDING），防刷
- 审批写操作全部记 `auth_audit_event`（SUBTASK_APPLY / SUBTASK_APPROVAL_APPROVE / PRODUCT_GRANT_AUTO）
- 越权防护：公司超管只能看/批本公司申请（`canApprove` + companyId 校验，现状保留）
- 平台超管看全部公司待审批（运维视角，现状保留）

## 六、审批边界铁律（V2.0.38 终版 · 主人 14:44 拍板：全部审批归子平台自管）

> 演进：V2.0.37 讨论时保留"License 级审批在主站"；主人 14:44 终版拍板**更进一步——所有审批（含产品开通申请）都放各子平台自己管理**，责任最清晰，避免将来多产品下公司超管的操作混乱。**本节为终版裁决，替代此前所有表述。**

| 审批类型                         | 审批位置      | 裁决                                                    |
| ---------------------------- | --------- | ----------------------------------------------------- |
| 产品开通申请（员工申请"能进产品"）           | **子平台内部** | V2.0.38 起主站不受理：`POST /api/sub/{code}/apply` 已降级返回 410 |
| 功能权限/角色申请（"产品内是什么角色"）        | **子平台内部** | 各子平台自建申请+审批 UI；主站不碰权限 schema                          |
| 主站 pending/approve/reject 接口 | —（无前端入口）  | 路由保留 + autoGrantOnApprove 联动保留（历史数据审计兼容），主站 UI 已全部下线  |
| 将来新审批类型                      | 默认放子平台    | 主站不扩权；除非主人明示某类审批必须全局统一                                |

**命名规范**：「集中审批」「开通审批」均废弃——主站不再有任何审批 Tab。

**主站已下线的 UI（V2.0.38）**：① 第 4 Tab 及审批面板；② KPI「待审批」卡片（4 卡变 3 卡：成员/License/已开通产品）；③ 产品列表「申请开通」按钮 → 改文案「请联系公司超管开通」；④ enter 403 弹窗「向公司超管申请开通」按钮 → 改引导文案。

## 六A、公司超管自动获得各子平台超管权限（主人 14:44 补充指令 · 契约）

**要求**：公司超管 SSO/账密进入任何子平台时，**自动拥有该子平台内的超管角色**，无需各子平台再手工配置。

**主站侧契约（已具备，无需改动）**：

- JWT claim **`is_tenant_admin`**：AuthService 签发时计算 = `平台超管 || CompanyUser.role=SUPER_ADMIN`（公司超管为 true）
- JWT claim `adm`（platform_admin 平铺字段）同步存在
- claim 在主站登录、refresh、/enter 签发的所有 token 中一致携带

**子平台侧实施规范（移交各窗口，GEOM 现状未达标需补）**：

1. sso-callback 验签后读 `is_tenant_admin` claim：
   - `true` → 本地账号 role 提升为该平台最高角色（GEOM=super_admin，其他平台按各自角色体系），**自动提升，无需人工**
   - `false` → 保持本地既有 role（无本地账号则按各家策略：拒绝 or 普通用户）
2. 本地已有账号 role 低于超管 → **登录时自动提升**（claim 是主站权威裁决，每次登录同步一次）
3. 本地 role 高于超管的情况不存在（超管即最高）；若本地账号被主站降权（claim=false 但本地是超管），以**主站 claim 为准降权**，并留审计日志
4. 审计：角色自动升降需写一条审计记录（谁、何时、从什么角色到什么角色、依据=lingyao claim）

**GEOM 现状差距**：`lingyao_sso.py` 的 `_resolve_user`/`sso_callback` **未读 `is_tenant_admin`**，role 完全取本地 sys_user.role → 公司超管 SSO 进 GEOM 不会自动获得 super_admin。需 GEOM 窗口按下述规范补齐（约 15 行改动）。其他平台按同规范实现。

## 七、后续待办（需主人拍板）

1. 子平台 5 家的登录页双轨改造排期（移交各窗口，本方案即交接文档）
2. 子平台内"产品内角色管理页"（给公司超管用）——建议随各子平台下个版本
3. 申请提交后是否要通知超管（站内信/邮件）——本期未做
4. SSO 登录链接生成接口（`/api/company-admin/sso-config/{provider}/login-link`）主站前端已接，后端如未实现需补（不影响扫码主流程）
5. **子平台内权限申请闭环**（V2.0.38 终版）：员工在子平台内的开通申请 + 功能权限申请 → 全部子平台内提交 → 公司超管在子平台内审批。审批通过后**产品开通授权**如何写回主站 → 见下条
6. **子平台 → 主站 grant 写回契约**（V2.0.39 方案 A 主站侧已落地）：
   - 主站新增 `POST /api/internal/grants/sync` + `POST /api/internal/grants/revoke`（InternalGrantsController）
   - **鉴权**：Authorization: Bearer <service JWT>——子平台用共享 LINGYAO_JWT_SECRET 签发，audience=lingyao-service，5 分钟有效；用户 token 冒充被 audience 校验拦截（E2E 已验证）
   - **写回复用** CompanyAdminService.grantProductToUser / revokeProductFromUser：license 校验、maxUsers 上限、幂等、审计全继承
   - **幂等**：重复 sync → ALREADY_ACTIVE；重复 revoke → IDEMPOTENT_OK；撤销后 sync → 复活 ACTIVE
   - **子平台参考实现**：`docs/grants_sync_client.py`（Python，GEOM 窗口可直接抄；其他语言平台按同契约签 token）
   - Body：`{ companyCode, productCode, username, approver? }`——三个 code 子平台均可从 SSO claim（ccode/pcode/username）直接取
   - 安全链：CsrfFilter 豁免 `/api/internal/`（服务间无 cookie）、SecurityConfig permitAll（接口内自验 service token）
7. **公司超管自动获得各子平台超管权限**（V2.0.38 契约已定，见六A）：GEOM 及各家 sso-callback 补读 `is_tenant_admin` claim 自动升降 role

## 九、V2.0.41 服务间通知接口（第七节第 3 条闭环 · 子平台复用主站 SMTP）

**`POST /api/internal/notify/email`** — 子平台服务端经主站 SMTP 发审批/安全通知，避免每家子平台重复配邮箱。

| 项 | 说明 |
|---|---|
| 鉴权 | 与 grants/sync 完全一致：service JWT（audience=lingyao-service，5 分钟，共享 LINGYAO_JWT_SECRET 签发） |
| Body | `{ caller, subject, body, to? }`——to 缺省发主站激活 EMAIL 通道超管邮箱 |
| 收件人白名单 | 仅允许 EMAIL 通道邮箱；任意收件人 → 403（防开放 relay 滥发） |
| 频控/审计 | 每次调用写 audit_log（INTERNAL_NOTIFY_EMAIL）；SMTP 未配置降级 503 |
| 签 token 参考 | `docs/grants_sync_client.py` 的 `make_service_token` 同款 |

**GEOM 使用场景**：员工在 GEOM 内提交权限申请 → GEOM 后端签 service token → 调本接口 → 公司超管 QQ 邮箱收到通知。

## 八、主/子平台分工表（主人 16:16 终版 · 明确版）+ 租户隔离验证（主人 16:27 补充要求）

### 8.1 分工表快照

**🔵 主站（本窗口）— 3 件事：**

| # | 任务                                                        | 状态                                                                                                  |
| - | --------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 1 | 工作台透出「复制入口链接」按钮（D4=B · 公司超管 scope）                        | ✅ **V2.0.40 完成**（纯前端：产品列表按钮「📋 复制链接」→「🔗 复制入口链接」，复制子平台 origin 不带 token；数据源 `/api/sub/list` baseUrl） |
| 2 | `/api/admin/sub-product/{code}/generate-link`（长期链接 365 天） | ✅ V2.0.31 已就绪（接口保留；V2.0.40 起前端默认走入口链接，token 链接按需调用）                                                 |
| 3 | `/api/internal/grants/sync` + `/revoke`                   | ✅ V2.0.39 已就绪（E2E 9 步全绿）                                                                            |
| ❌ | 不做审批 UI（V2.0.38 终版已下放子平台）                                 | —                                                                                                   |
| ❌ | 不做分类管理（分类是子平台内部概念）                                        | —                                                                                                   |

**🟢 GEOM（GEOM 窗口）— 9 件事**（本窗口不越界，仅转述主人分工）：  
sys_user 加 approval_status；brand_category 软删除（status + /disable /enable，删 DELETE）；lingyao_sso.py 审批拦截（pending 拒绝进工作台）；users.py approve/reject/assign-categories（联动 grants_sync）；前端用户管理页（待审批 Tab + 审批按钮 + 分配分类）；PendingApprovalPage + 路由守卫；LingyaoShareLinkButton；品牌管理页软删除改造；E2E + commit + docs。

**⚪ 其他平台**：MPDM / AIDD / DINFO / POR-M / MED-PPT 规范已起草不实施，各窗口自取。

### 8.2 租户隔离验证（V2.0.40 · E2E 实证）

主人 16:27 要求「注意不同租户的隔离」，全链路 7 项验证：

| # | 验证点                                                                                                                                                                        | 结果                             |
| - | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ |
| 1 | grants/sync 跨租户写授权：B-DEMO 名下给 a_user1(A-DEMO)                                                                                                                              | ✅ 403「用户 a_user1 不属于公司 B-DEMO」 |
| 2 | grants/sync 不存在用户                                                                                                                                                          | ✅ 404                          |
| 3 | grants/revoke 跨租户撤销                                                                                                                                                        | ✅ IDEMPOTENT_OK 不命中他司授权        |
| 4 | 公司超管 token 租户绑定：b_admin cid=2/ccode=B-DEMO，company-admin/me 只见本公司                                                                                                          | ✅                              |
| 5 | 入口链接按钮 scope：a_user1(普通员工) canManage=false 按钮隐藏                                                                                                                            | ✅                              |
| 6 | generate-link License 租户校验：b_admin 生成未开通产品 → 拒；已开通 → token 绑 cid=2/B-DEMO                                                                                                  | ✅                              |
| 7 | /enter 个人授权粒度：b_admin enter GEO 直通是**合法**（其本人持 B-DEMO 公司的 GEO 个人 grant，platform_token sub_product_grants=['GEO']，ccode=B-DEMO），校验为 (companyId, productId, userId) 三元组，无跨租户 | ✅                              |

**D4=B 入口链接的租户隔离增益**：旧 365 天 token 专属链接内嵌 `cid/ccode/uid`，转发泄露=冒充该公司身份进入子平台；新的纯 origin 入口链接**不含任何身份信息**，员工打开后由子平台登录页 + 主站 SSO 验签重新确立身份，租户边界在每次登录时重建。
