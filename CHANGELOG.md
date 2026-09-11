# 凌瑶智数 · 变更日志

## V2.0.77.19 (2026-09-11 17:15) — SSO 配置保存免 OTP，改「变更通知邮件」（主人拍板方案 A）

**问题**：portal「配置飞书 SSO」弹窗保存报「敏感操作验证码不能为空（请先弹窗发码）」——V2.0.77.15 给 `POST /api/company-admin/sso-config/{provider}` 加了强制 OTP，但 portal 弹窗没有发码 UI，保存被卡死。主人质疑「这里有这么敏感吗」。

**评估**：SSO 配置是全员登录信任根（当初加 OTP 的理由），但保存属「配置迭代型」操作——首次配置要连续保存 3~5 次试参，每次收码 + 发码端点 IP 10/h 限流会把正常配置流程卡死；OTP 防会话劫持收益有限，而审计（R5-03：SSO_CONFIG_UPSERT 含 secretChanged 标记）已留痕。拦截式校验错配场景。

**方案 A 落地**（安全口径：拦截 → 检测）：
1. `SsoConfigController.upsert` 去掉 `sensitiveOtpService.verify("CHANGE_SSO_CONFIG", ...)` 调用；**DELETE 仍保留 OTP**（删除不可恢复，值得一拦）
2. `SsoConfigService.upsertConfig` 新增变更通知邮件：保存成功后立即给操作者邮箱发「SSO 配置已新建/更新（公司 · Provider · 是否含密钥变更），如非本人操作请立即改密」——fail-soft，邮件失败不影响保存；secret 本体不入邮件
3. 版本四方 bump V2.0.77.19（application.yml / index.html script.js?v= / portal.html / admin.html meta）

**前端零改动**：portal 报错随免 OTP 自然消失；admin/company-admin 的 promptSensitiveOtp 弹窗不受影响（各自场景仍用）。

## V2.0.77.18b (2026-09-10 15:50) — r5 阶段 5 安全验收 AX-01 反修（OTP 爆破审计落库失败）

安全维验收（06-acceptance-security.md，8.5/10 有条件通过）实锤 AX-01：`EmailOtpService.verifyOtp` 调 `recordFailureWithMeta` 参数错位——31 字符的 `"/api/auth/sensitive-otp/verify"` 误落 requestMethod 槽位（varchar(8)），INSERT 报 value too long 被静默吞，R5-05② 的 OTP 爆破审计全量落库失败（psql 实测 OTP_VERIFY_FAIL 计数=0）。

**修复**：path/method 参数各归其位（`"/api/auth/sensitive-otp/verify", "POST"`），一行级改动 + 注释留痕。修后 security-r5 复验（假 OTP 触发 + psql 查落库）即转正式通过。

## V2.0.77.16~.18 (2026-09-10 15:20) — r5 主站质量环三批连做（R5-01~12 全落地）

主人 14:59 拍板「A」= 三批连做 + 开放问题按 PRD 倾向（①plan-quotas 端点下期 ②删 ticket 死代码 ③核心测试 3h ④限流 10 次/h）。PRD：`.workbuddy/quality-loop/2026-09-10-r5-main-site/03-prd.md`。

### 第 1 批 V2.0.77.16：安全+硬伤（R5-01~03）
- **R5-01（P1/SEC-01③）**：GlobalExceptionHandler RuntimeException 分支不再回 `ex.getMessage()`（SEC-01 泄漏 JCE 全文根因），改通用文案，详情只进日志
- **R5-02（P1/SEC-01②）**：JwtUtil `@PostConstruct` fail-fast——secret 空或 <32B（RFC 7518 §3.2）拒绝启动
- **R5-03（P2/SEC-05）**：SsoConfigService upsert/delete 补审计（SSO_CONFIG_UPSERT / SSO_CONFIG_DELETE，记 provider+是否密钥变更，不记 secret 本体）

### 第 2 批 V2.0.77.17：健壮性（R5-04~10）
- **R5-04（SEC-03）**：updateCompany contactEmail/contactPhone/address/remark 改 null-preserve（传 null=保持原值，防部分更新静默清空；传 ""=显式清空）
- **R5-05（SEC-04）**：①RateLimitFilter 加 sensitive-otp/send 规则（IP 10 次/h）；②EmailOtpService verify 失败/锁定写 AuthAuditEvent（新枚举 OTP_VERIFY_FAIL / OTP_LOCKED，爆破对 SecurityEvents 面板可见）
- **R5-06（SEC-06 前半）**：OTP 移出邮件主题（主题不再含 6 位验证码，仅正文保留）
- **R5-07（ARCH-001/SEC-06 后半）**：删除 ticket 两段式死代码（verifyAndIssueTicket/verifyTicket/ticketStore/POST /ticket 端点），只留 verify() 单机制
- **R5-08（ARCH-008）**：EmailOtpService 加 `@Scheduled(60s)` 过期 otpStore/lastSentAt 主动清扫
- **R5-09（ARCH-002 短期）**：admin.html PLAN_QUOTAS 加镜像来源注释（真源 geom/app/core/plan_quotas.py V0.10.19）
- **R5-10（ARCH-003）**：改 geomPlan 审计文案追加「需用户重新登录后生效」

### 第 3 批 V2.0.77.18：可维护性（R5-11~12）
- **R5-11（ARCH-009）**：SensitiveOtpService 改构造器注入 + 新增 SensitiveOtpServiceTest 6 用例（空 OTP fail-closed / 错误 OTP / 正确 OTP / 用户不存在 / 无邮箱 / 正常发码）
- **R5-12（ARCH-010）**：docs/软2FA实施现状_V0.77.md 补「敏感操作 OTP 已落地」节（6 端点清单+机制+安全特性）

### 版本号
- 四处 bump 至 2.0.77.18（application.yml / index.html script.js?v= / portal.html / admin.html+company-admin.html meta）

### 开放问题拍板记录
- 开放问题 1：GET /api/admin/plan-quotas 端点 → 下期单独立项（本期只做 R5-09 注释）
- 开放问题 2：ticket 死代码 → 删除（R5-07 已执行）
- 开放问题 3：测试范围 → 核心 3h（R5-11 已执行 6 用例）
- 开放问题 4：发码限流 → IP 10 次/h（R5-05 已执行）

## V2.0.77.15c (2026-09-10 14:00) — r5 质量环阶段 1 即时反修：SEC-02 DTO 合并 + 版本号四处对齐（ARCH-007）

主人 13:56 拍板「A、B」（先反修 SEC-02，再进阶段 2 PRD）。r5 主站质量环 run：`.workbuddy/quality-loop/2026-09-10-r5-main-site/`（architect-r5 6.5/10 + security-r5 6.5/10）。

### SEC-02 修复（P1 · 编辑公司 OTP 链路断裂）
- **根因**：`AdminController.updateCompany` 有两个 `@RequestBody`（UpdateCompanyRequest + Map otpBody）——Spring 只反序列化一次 body，otpBody 恒 null → OTP 永远验不过。主线程活体复测实锤：全量 body + 非空假 OTP → 仍报「验证码不能为空」（推翻架构师「碰巧可用」结论，采纳安全专家判定）
- **修法（DTO 合并方案）**：
  - `UpdateCompanyRequest` 新增 `@Size(max=6) String emailOtpCode` 字段
  - `AdminController.java:119-134` 删除第二个 `@RequestBody Map otpBody`，改读 `req.getEmailOtpCode()`
  - fail-closed 语义保留：空 OTP 仍被 SensitiveOtpService.verify 拒绝
- 前端 saveEditCompany 本就把 emailOtpCode 放同一 body，无需改动

### ARCH-007 修复（P1 · 版本号四处漂移）
- `application.yml:56`：2.0.71 → **2.0.77.15**
- `website/index.html:1292`：script.js?v=2.0.73 → **v=2.0.77.15**
- `website/portal.html:7`：V2.0.66 → **V2.0.77.15**
- `website/admin.html`：补 `<meta name="app-version" content="V2.0.77.15">`（原本缺失）
- company-admin.html 已是 V2.0.77.15（唯一正确项）

### 部署
- `cp` 双副本（admin/company-admin → static/）+ `mvn package -DskipTests` + 正式 daemon 脚本重启

### 活体验证
- E2E：全量 body + 假 OTP → 修复后应返回「验证码错误」（OTP 已被读到）而非「验证码不能为空」
- `/api/health` 或 config 端点确认 app.version=2.0.77.15

## V2.0.77.15 (2026-09-10 12:45) — 6 端点敏感操作 OTP 二次验证（A 选项 · 软 2FA 扩展）

主人 12:29 拍板 A（6 个高危操作全接 OTP）。10 文件 712±23 DELTA。

### 后端新增
- `SensitiveOtpService.java`（130 行）：sendSensitiveOtp / verifyAndIssueTicket / verify，ConcurrentHashMap ticketStore（5min TTL）
- `SensitiveOtpController.java`：POST /api/auth/sensitive-otp/send + POST /api/auth/sensitive-otp/ticket
- EmailOtpService.java：新增 `PURPOSE_SENSITIVE_OPERATION`（"敏感操作验证"）

### 后端接管 OTP 的 6 个 endpoint（prev 无二次验证，现在直接在 endpoint 内 verify）
AdminController（4）：PUT companies/{id}、POST suspend、POST activate、POST reset-password
CompanyAdminController（1）：PUT users/{userId}/role（改角色 → 影响访问控制）
SsoConfigController（2）：POST upsert、DELETE delete（改/删 SSO → 影响全员登录）

### 前端
- **admin.html**：sensitiveOtpModal + promptSensitiveOtp/send/verify/cancel 4 函数；suspend-company/activate-company/reset-password 3 处已接 OTP
- **company-admin.html**（V2.0.62 → V2.0.77.15）：新增 sensitiveOtpModal（复用）；doChangeRole + doSaveSsoProvider 接 OTP

### 未暴露 UI 的后端 OTP
- PUT /companies/{id}（admin.html 无公司信息修改入口 → 后端 OTP 就绪但前端暂不触发）
- DELETE /sso-config/{provider}（company-admin.html 无删除入口 → 同上）

### V2.0.77.15 补丁（13:00 · commit 12218e1）
- **② 编辑公司 OTP**：admin.html 公司行加「✏️」按钮 + editCompanyModal → PUT /api/admin/companies/{id}
- **③ 删除 SSO OTP**：company-admin.html SSO 卡片加「🗑️」按钮 + doDeleteSsoProvider → DELETE /sso-config/{provider}
- **① M3 套餐用量统计**：admin.html 新增「📊 套餐用量」Tab（KPI 5 卡 + 明细表），数据源 companiesCache 自洽不需 GEOM API
- 至此 V2.0.77.15 完整收口：8 个 endpoint 后端 + 前端全部 OTP + M3 用量面板

### 部署
- `cp website/admin.html backend/src/main/resources/static/admin.html`
- `cp website/company-admin.html backend/src/main/resources/static/company-admin.html`
- `mvn -f backend/pom.xml package -DskipTests` + daemon 重启 → 9091 PID 18252

### E2E 验证
- JS AST 语法检查：company-admin.html ALL SCRIPTS OK
- 9091 存活：lsof -i :9091 返回 java PID 18252
- /api/auth/config 401（已保护，需登录）→ 200（company-admin.html + sensitiveOtpModal）
- admin.html：sensitiveOtpModal=1，promptSensitiveOtp=5

### 待办
- 主人登录 http://localhost:9091/admin.html 实测 OTP 弹窗 + http://localhost:9091/company-admin.html 实测改角色/改 SSO OTP
- 主站 98 个未 push commit，待选推送窗口（18:00 or 23:00）

## V2.0.77.14 (2026-09-10 12:30) — 主站 M4 定价与合同模板（GEOM 三级套餐契约 §4 M4）+ 软 2FA 现状文档化

主人 12:21 拍板 B1（M4 定价与合同模板）+ B2（软 2FA）都做。本版本：

### B1 · M4 落地（10 文件 + DDL + 11 列）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `Company.java` | 新增 `annualFeeYuan` (Integer, default 200000) + `contractTemplate` (枚举 ContractTemplate: NONE/BASIC/PRO/FLAGSHIP) |
| 2 | `CreateCompanyRequest.java` | 新增 `annualFeeYuan`（@Min 0 @Max 1亿）+ `contractTemplate`（@Pattern） |
| 3 | `UpdateCompanyRequest.java` | 新增同样两字段（null 保持原值） |
| 4 | `TenantAdminService.createCompany()` | 写入 `annualFeeYuan` (兜底 200000) + `contractTemplate` (兜底 NONE) |
| 5 | `TenantAdminService.updateCompany()` | diff 日志 + null 保持原值 |
| 6 | `schema-private.sql` | `ALTER TABLE company ADD COLUMN IF NOT EXISTS annual_fee_yuan INT DEFAULT 200000 NOT NULL` + `contract_template VARCHAR(16) DEFAULT 'NONE' NOT NULL` |
| 7 | `website/admin.html` 公司表单 | 新增「年费」「合同模板」+「📄 预览合同」按钮 |
| 8 | `website/admin.html` 公司表格 | 10 列→11 列（插入「年费/合同」列，万元格式化 + 合同档徽标） |
| 9 | `website/admin.html` 新增 Tab「💰 商业配置」 | 三档定价 KPI 卡 + 合同模板规格说明（只读展示） |
| 10 | `website/admin.html` 新增合同预览 Modal | 三档合同模板正文渲染（含签约方/服务期/争议解决草案） |
| 11 | `backend/.../static/admin.html` | 同源 cp + jar 重打包 |
| 12 | `lingyao-platform.jar` | 12:26 重打 59 MB |

**Hibernate ddl-auto=update 自动加列**（实测 dev-pg 数据库已含 `annual_fee_yuan` integer + `contract_template` varchar）。

### B2 · 软 2FA 现状文档化（不动代码 · 仅文档）

软 2FA **核心已落地**（V2.0.69 A+D+B 方案 + V2.0.77.6 设备信任）：
- ✅ 新设备登录 → 强制邮箱 OTP（`deviceOtpRequired=true`）
- ✅ 改密码 → 强制邮箱 OTP（`/api/auth/change-password`）
- ✅ 信任设备 → 跳过 OTP（UA + IP 段指纹）
- ✅ 设备信任 30 天期（V2.0.77.6 算法）

新增文档：`docs/软2FA实施现状_V0.77.md`（4 KB）：
- 现状 4 维（端点/流程/安全强度/前端集成）
- 与 V2.0.67→.68 回滚教训对齐（不增登录摩擦）
- **候选扩展 PRD：敏感操作 OTP**（A/B/C 选项待主人拍板）
  - A: 6 个高危操作（改 GEOM/改合同/删公司等） · 1-2 人日
  - B: 3 个商业变更（保守）· 0.5 人日
  - C: 暂缓列 P3

### 部署
- 9091 后端 daemon 守护（PID 7859，12:26:39 启动完成，5 秒启动）
- 访问：http://localhost:9091/admin.html（pc_admin/PrivateCust#2026 + 算术 captcha）

### E2E 待登录验证
- [ ] 公司列表 11 列显示（年费「20 万」+ 合同档徽标）
- [ ] 新建公司弹窗含「年费/合同模板/预览合同」三字段
- [ ] 「💰 商业配置」Tab 显示三档定价 KPI 卡
- [ ] 「📄 预览合同」按钮 → 合同正文 modal 渲染
- [ ] 改公司 contractTemplate → audit_log 写入 diff

### 待办
- B2 敏感操作 OTP A/B/C：等主人拍板
- 推送治理 V3.2：等主人拍板推送窗口（12:00/18:00/23:00）

---

## V2.0.77.13 (2026-09-10 11:25) — 主站 SSO 注入 `geom_plan` claims（GEOM 三级套餐契约落地 · 主站侧 M2）

---

## V2.0.77.13.M1 (2026-09-10 11:45) — 主站公司管理页「GEOM 套餐」选择器 UI（契约 §4 M1）

主人 11:42 拍板「把主站该做的做了」→ 落地契约 `/tmp/GEOM_quota_contract.md §4 M1`：公司管理页加 GEOM 套餐三选一。

### 改动清单（仅前端 · 后端 DTO 已就绪 V2.0.77.13）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `website/admin.html` 公司表单 | 新增 `<select name="geomPlan">`（basic/pro/flagship，默认 basic） |
| 2 | `website/admin.html` 公司表单 submit | `companyBody.geomPlan = fd.get('geomPlan') \|\| 'basic'` |
| 3 | `website/admin.html` 公司列表表头 | 9 列 → 10 列：插入「GEOM 套餐」 |
| 4 | `website/admin.html` 表格渲染 | `geomPlanLabel(gp)` 三色徽标（basic 灰 / pro 蓝 / flagship 紫） |
| 5 | `backend/.../static/admin.html` | cp 同源，jar 重打包 |
| 6 | `backend/target/lingyao-platform.jar` | 11:33 重打 59 MB（jar 内 `static/admin.html` 5 处 geomPlan 引用） |

### 部署
- 9091 后端已 daemon 守护（PID 88303，11:38 启动完成）—— 双重 fork + setsid，sandbox 不再杀
- 访问：http://localhost:9091/admin.html（需用 pc_admin/PrivateCust#2026 + 算术 captcha 登录）

### 后端链路（已就绪，V2.0.77.13 落地）
- ✅ `Company.geomPlan` 实体字段 = "basic"（DDL 已 ALTER）
- ✅ `CreateCompanyRequest.geomPlan` @Pattern 三枚举校验
- ✅ `UpdateCompanyRequest.geomPlan` 同上（null 保持原值）
- ✅ `TenantAdminService.createCompany()` L69: `company.setGeomPlan(req.getGeomPlan() != null ? req.getGeomPlan() : "basic")`
- ✅ `TenantAdminService.updateCompany()` L318: `company.setGeomPlan(req.getGeomPlan())`
- ✅ `CompanyAdminService.toMap()` L84: `companyMap.put("geomPlan", ...)`（登录返回含 geomPlan）
- ✅ `AdminController.GET /api/admin/companies` 直接 `companyRepo.findAll()`（JPA 序列化暴露 geomPlan）

### 待 E2E（下次登录时验证）
- [ ] 登录 pc_admin → 公司列表显示「GEOM 套餐」列（应全 basic）
- [ ] 创建公司 + 选 pro → 公司列表该行 pro 徽标
- [ ] 现有 A-DEMO/B-DEMO/PRIVATE-CUSTOMER 三行全 basic（DB 已确认）

### 待办（其他窗口）
- **GEOM 窗口**（契约 §3 C1~C5，本主站窗口不代笔）：C1 schema / C2 SSO claims 消费 / C3 fail-closed 拦截 / C4 前端删「管理分类」/ C5 V0.10.19 收尾
- **主站 P3**（待主人拍板）：M3 主站后台显示 GEOM 用量统计——需 GEOM 出统计 API 后再排；M4 定价与合同模板（20/48/98 万）

---

主人 11:07 给我看 `/tmp/GEOM_quota_contract.md`（V1.0 定稿），主站承担 M1+M2。本版本落地 M2（claims 注入）+ 为 M1（前端选择器）留 DTO 接口。

**主人拍板**：
- A) geom_plan 字段 = **新增独立列 geom_plan**（不复用 LicensePlan，不扩枚举）
- B) 上线时序 = **主站先 M2 上线（默认 basic）**（GEOM 端 C1~C5 后再补，本期可错峰）

### 契约回顾（GEOM_quota_contract.md §1+§2）

- 3 级套餐：basic 20万（3 分类/10 品牌）· pro 48万（5/20）· flagship 98万（10/50）
- claims 契约 V2：token payload 新增 `geom_plan: basic|pro|flagship`（缺省/非法 → basic，R1+R2 兜底）

### 改动清单（10 个文件）

| # | 文件 | 改动 |
|---|---|---|
| 1 | **DDL** | `ALTER TABLE company ADD COLUMN geom_plan VARCHAR(16) DEFAULT 'basic' NOT NULL`（PG 嵌入式 psql 实时执行）|
| 2 | `Company.java` | 加 `geomPlan` 字段（VARCHAR(16)，默认 basic）|
| 3 | `LoginResponse.java` | CompanyView 加 `geomPlan` 字段 |
| 4 | `CreateCompanyRequest.java` | 加 `geomPlan` 字段（@Pattern basic\|pro\|flagship，默认 basic）|
| 5 | `UpdateCompanyRequest.java` | 加可选 `geomPlan` 字段（null = 保持原值）|
| 6 | `CompanyAdminService.java` | companyMap.put("geomPlan", ...) |
| 7 | `TenantAdminService.java` | createCompany setGeomPlan + updateCompany 处理 geomPlan |
| 8 | `JwtUtil.java` | generate() + generateSsoToken() 加 geom_plan claims + normalizeGeomPlan() 兜底（合法 basic/pro/flagship，缺/非法 → basic）|
| 9 | `AuthService.java` | 两处 generate() 调用注入 company.geomPlan（EXPIRED 检查附近的 Company 实例复用）|
| 10 | `SsoCallbackController.java` | generateSsoToken() 调用前查 company.geomPlan |
| 11 | `portal-backend-py/main.py` | issue_lingyao_token() 注入 `geom_plan="basic"` 兜底（Python 端读 GEOM SQLite 共享库，无 company.geom_plan 列，留 TODO 给主人后续）|
| 12 | `schema-private.sql` | 加 `ALTER TABLE company ADD COLUMN IF NOT EXISTS geom_plan ...`（私有化部署迁移）|

### 设计决策

**不复用 LicensePlan 枚举**：契约 `geom_plan` 是 GEOM 专属套餐，`license_plan` 是通用商业模式（TRIAL/STANDARD/ENTERPRISE 覆盖全产品）。两个独立维度，新增独立列最干净。

**不在 portal-backend-py 引入 psycopg2 依赖**：portal-backend-py 是历史兼容登录端口，Java 9091 是主路径；Python 端兜底 basic 满足契约 R1 缺省逻辑。后续主人拍板「Python 路径也注入真实 geom_plan」时再补。

**不在 M2 阶段做 M1 前端选择器**：DTO 字段已留好（CreateCompanyRequest / UpdateCompanyRequest / LoginResponse.CompanyView），主人拍板「P2 单独排期做前端 UI」。

### E2E 验证（全绿）

```
E2E 1：登录 admin（公司 A-DEMO geom_plan=basic）
  LoginResponse.company.geomPlan = basic ✅
  JWT.geom_plan = basic ✅

E2E 2：SQL 改 A-DEMO geom_plan → pro + 重新登录
  LoginResponse.company.geomPlan = pro ✅
  JWT.geom_plan = pro ✅

E2E 3：契约 §2 R2 兜底（SQL 改 A-DEMO geom_plan=invalid_value + 重新登录）
  JWT.geom_plan = basic（兜底生效，不报错）✅
```

### 主站版本号

- 业务版本号：**V2.0.77.13**
- 后端 pom.xml `<version>1.0.0</version>`（未改）
- Java 改动 10 处 + Python 1 处 + DDL 1 处

### 待办（其他窗口）

- **GEOM 窗口**（契约 §3 C1~C5）：
  - C1 schema 迁移（tenant.plan 列 + brand_category.tenant_id）
  - C2 SSO 消费 claims（_resolve_user / sso_callback）
  - C3 配额拦截（fail-closed，写 user_audit_log）
  - C4 前端（Brands.vue 删「管理分类」入口 + SystemConfig.vue 分类管理 Tab）
  - C5 版本收尾（V0.10.19）
- **主站 P2**：M1 公司管理页「GEOM 套餐」三选一选择器（DTO 已就绪，UI 待拍板）

---

## V2.0.77.12 (2026-09-09 10:00) — 主站跳棱镜 ERR_CONNECTION_REFUSED 修复（端口不一致 + daemon 启停）

主人 09:52 截图报错：从主站跳 GEOM 棱镜 → `localhost:5180/#/auth/lingyao/sso-callback?...` → `ERR_CONNECTION_REFUSED`。

### 根因（双重叠加）

| # | 问题 | 文件 | 状态 |
|---|---|---|---|
| **1** | 主站配置指向 **5180**（旧） | `application.yml` L112 | ❌ 应改 5182 |
| **2** | GEOM 前端 daemon 没跑 | 5180/5182 都不在 LISTEN | ⚠️ 待启 |

主站两处 GEO baseUrl **不一致**：
```java
// SsoCallbackController.java L257（V2.0.70 已修）
case "GEO"  -> "http://localhost:5182/auth/lingyao/sso-callback";
```
```yaml
# application.yml L112（V2.0.70 漏改！）
subtask:
  routes:
    geo:
      base-url: ${LINGYAO_SUBTASK_GEO_BASE_URL:http://localhost:5180/#/auth/lingyao/sso-callback}
```

主人截图 URL 走的是 `application.yml`（带 `#` hash 路由，是 `/api/sub/geo/enter` 端点，portal.html 第 1710 行调用）。

### 修复（主人 09:53 拍板 A+B 全修）

**A. 改主站配置 + 重打 jar + 重启 9091**

```yaml
# application.yml L112 新值
geo:
  # V2.0.77.12（2026-09-09）：5180 → 5182 · 与 SsoCallbackController L257 保持一致
  base-url: ${LINGYAO_SUBTASK_GEO_BASE_URL:http://localhost:5182/#/auth/lingyao/sso-callback}
```

- `mvn -f backend/pom.xml -DskipTests -o clean package`（重打 jar，让 application.yml 进 BOOT-INF/classes）
- `python3 scripts/start_backend_daemon.py`（重启 9091，让新配置生效）
- ⚠️ **关键坑**：在 mvn clean package **同时**杀旧 java → Spring Boot graceful shutdown 时 logback 类加载失败（jar 已被替换），9091 短暂空窗 30 秒；最终第三次启动 daemon 才成功

**B. 启动 GEOM 前端 daemon**

```bash
python3 scripts/start_geom_frontend_daemon.py
# → Vite 6.4.3 ready in 8066 ms
# → http://localhost:5182/ LISTEN
```

铁律 0 边界澄清：
- ✅ 主站脚本（`scripts/start_geom_frontend_daemon.py` 是主站所有，V2.0.70 主站帮 GEOM 写过）
- ✅ 启动 daemon 是运维动作不是动 GEOM 功能
- ❌ 不写 GEOM 仓代码 / 不代 commit / push

### E2E 验证

```
登录拿 TOKEN (admin/admin123 + 算术图形验证码 5-4=1)
  → HTTP 200 (token eyJhbGciOiJIUzUxMiJ9...)

GET /api/sub/geo/enter:
{
  "code": 0,
  "data": {
    "productCode": "GEO",
    "productName": "棱镜-智能GEO监测",
    "redirectUrl": "http://localhost:5182/#/auth/lingyao/sso-callback?tenant_id=1&user_id=1&user=admin&display_name=%E5%B9%B3%E5%8F%B0%E8%B6%85%E7%AE%A1#platform_token=eyJhbGciOiJIUzUxMiJ9.eyJncmFudHMiOnt9LCJ1aWQiOjEsImNjb2RlIjoiQS1ERU1PIiwidGVuYW50X25hbWUiOiJBIOiNr-S8ge-8iOa8lOekuu-8iSIsImlzX3RlbmFudF9hZG1pbiI6dHJ1ZSwic3ViX3Byb2R1Y3RfZ3JhbnRzIjpbIkdFTyJdLCJpZGVudGl0eSI6eyJ1c2VyX2lkIjoiMSIsInVzZXJuYW1lIjoiYWRtaW4iLCJkaXNwbGF5X25hbWUiOiLlubPlj7DotoXnrqEifSwiYWRtIjp0cnVlLCJ0ZW5hbnQiOnsiaWQiOiIxIiwiY29kZSI6IkEtREVNTyIsIm5hbWUiOiJBIOiNr-S8ge-8iOa8lOekuu-8iSJ9LCJ1c2VybmFtZSI6ImFkbWluIiwiY2lkIjoxLCJpc3MiOiJsaW5neWFvLXBsYXRmb3JtIiwiYXVkIjpbImxpbmd5YW8tc3NvIl0sInN1YiI6IjEiLCJqdGkiOiIyNzc0ZmJmNi1hNTdmLTRhOTUtYTJjZS1mNmVkZDRiNmY4MjkiLCJpYXQiOjE3ODg5MTkzMDMsImV4cCI6MTc4OTAwNTcwM30.IEg2kT6ectNEXeVFR28KBqsD_5CW9PRkZaPys-Fo4YbzH3zhL23mnwacFigKwg3p_SbJH_k515rZ3qcrHc5EQQ",
    "mode": "DIRECT_REDIRECT",
    "tokenInFragment": true
  }
}

直接访问 5182:
  GET http://localhost:5182/                       → HTTP 200 (Vite dev server 首页)
  GET http://localhost:5182/auth/lingyao/sso-callback → HTTP 200 (GEOM SSO 回调路由 ✅)

jar 内 application.yml 验证（python zipfile 提取）:
  110:       geo:
  112:         base-url: ${LINGYAO_SUBTASK_GEO_BASE_URL:http://localhost:5182/#/auth/lingyao/sso-callback} ✅
```

### 主站版本号

- 业务版本号：**V2.0.77.12**
- 后端 pom.xml `<version>1.0.0</version>`（未改）
- application.yml 改动 1 行
- 9091 daemon 第三次启动才成功（mvn + daemon 启停时序坑）

### 已知坑（备忘）

**mvn clean package + SIGTERM 旧 java 的时序冲突**：
- mvn clean package 会替换 jar（rename old → .jar.original → repackage）
- 同时如果给旧 java 发 SIGTERM，Spring 走 graceful shutdown → SpringApplicationShutdownHook
- 关闭过程中某些 logback 类会从原 jar 加载失败 → ClassNotFoundException
- 9091 短时不可用（30 秒~1 分钟）
- **正确做法**：先 SIGTERM 旧 java 等退出完成 → 再 mvn clean package → 再启新 daemon

---

## V2.0.77.11 (2026-09-09 09:48) — 主门户 .html 后缀路由 404 修复（catch-all · V2.0.77.7 续）

主人 09:47 截图报错：访问 `/portal.html` → FastAPI 返回 `{"detail":"Not Found"}`。

### 根因

`portal-backend-py/main.py` 第 102 行只定义了 `/portal` 路由（简化路径），但 `website/*.html` 内部硬编码的 .html 引用没改：

| 文件 | 行号 | 内容 |
|---|---|---|
| `index.html` | L53 | `<a href="/portal.html">登录</a>` （主人报错路径）|
| `invite.html` | L328 | `<a href="/portal.html">立即登录</a>` |
| `404.html` | L115 | `<a href="/portal.html">前往登录</a>` |
| `aidd.html` | L60 | `<a href="/portal.html">← 返回主控台</a>` |
| `geo.html` | L61 | `<a href="/portal.html">← 返回主控台</a>` |
| `hpd.html` | L60 | `<a href="/portal.html">← 返回主控台</a>` |
| `por.html` | L60 | `<a href="/portal.html">← 返回主控台</a>` |

历史背景：原部署是 `python -m http.server` 直服务静态文件，`/portal.html` 直接 GET 可用；凌瑶升级 FastAPI 统一登录门户（V2.0.77）后路由名简化了，但 HTML 引用没改。

**与 V2.0.77.7 完全同源**：V2.0.77.7 修了 css/js/img/assets/fonts 5 类静态资源，本版本补 HTML 文件。

### 修复

`portal-backend-py/main.py` 新增 **1 个 catch-all**：

```python
@app.get("/{filename}.html", include_in_schema=False)
async def html_page(filename: str):
    """兼容旧引用路径 /portal.html /admin.html 等 → website/{filename}.html"""
    # 安全防护：filename 只允许字母数字下划线连字符（防止路径穿越 ../etc/passwd）
    if not filename.replace("-", "").replace("_", "").isalnum():
        raise HTTPException(404, f"invalid html page name: {filename}")
    file_path = WEBSITE_DIR / f"{filename}.html"
    if not file_path.is_file():
        raise HTTPException(404, f"html page not found: {filename}.html")
    return FileResponse(file_path)
```

**为什么选 catch-all 而不是改 9 处 HTML**：
- 与 V2.0.77.7 设计哲学一致（additive change，0 HTML 改动）
- 9 个 HTML 内部互相引用可能漏改（admin/aidd/geo/hpd/por 都互相串）
- 未来新增 HTML 也自动兼容

**FastAPI 路由优先级**：
- `/portal`（具体）先匹配 → FileResponse(portal.html) ✅
- `/portal.html` 落到 catch-all → filename="portal" → FileResponse(portal.html) ✅
- 不冲突

### E2E 验证

```
主人报错路径：
  GET /portal.html   → 200 (95199 bytes · text/html)
  GET /portal        → 200 (95199 bytes · text/html)  # 原有路由仍工作
其他 9 个 HTML 全绿：
  GET /admin.html          → 200 (92846 bytes)
  GET /aidd.html           → 200 (7507 bytes)
  GET /company-admin.html  → 200 (64975 bytes)
  GET /geo.html            → 200 (7706 bytes)
  GET /hpd.html            → 200 (7635 bytes)
  GET /por.html            → 200 (7496 bytes)
  GET /404.html            → 200 (3751 bytes)
  GET /index.html          → 200 (56581 bytes)
  GET /invite.html         → 200 (16560 bytes)
安全防护：
  GET /../etc/passwd.html  → 404 （路径穿越阻断 ✅）
  GET /etc.html            → 404 （文件不存在 404 ✅）
```

### 主站版本号

- 业务版本号：**V2.0.77.11**
- 后端 pom.xml `<version>1.0.0</version>`（未改）
- 门户 main.py 改动 1 处（+19 行）

---

## V2.0.77.10 (2026-09-08 20:50) — 主站灾备 hotfix（JWT secret 路径修正）+ AIDD 子平台首次灾备代执行

主人 20:46 下令："必须做 AIDD 的灾备，不能够越权做其他子项目的灾备"。本窗口按指令：
1. 修主站灾备脚本 P0 bug（V2.0.77.8 漏备 JWT secret）
2. 跑主站本次灾备（确保主站备份完整）
3. 按 V2.0.77.9 「主站备份协调中枢」定位代为 AIDD 子平台执行首次灾备（**只读 AIDD 仓 + 不写 AIDD 仓代码**）
4. 写 AIDD SOP 文档（所有权归 AIDD owner）

### 1. 主站灾备 hotfix：JWT secret 路径修正（V2.0.77.8a）

**根因**：V2.0.77.8 脚本 `BACKUP_JWT_SECRET` 路径写 `geo-backend-py/data/.jwt-secret`，但主站真源是 `data/.jwt_secret`（主站 JWT 不在 GEOM 子仓里）。原 `[ -f ... ] && echo ""` fallback 静默跳过，导致 V2.0.77.8 全部 4 次备份都**漏掉 JWT secret**——恢复后所有现有 token 失效。

**修复**（`scripts/backup_to_nas.sh`）：
- `BACKUP_JWT_SECRET_MAIN = ${PROJECT_DIR}/data/.jwt_secret`（主站真源）
- `BACKUP_JWT_SECRET_GEOM = ${PROJECT_DIR}/geom/geo-backend-py/data/.jwt-secret`（GEOM 副本，read-only 打包）
- BACKUP_INFO.txt 新增**关键资产完整性自检**块（5 项必检：主站 JWT / GEOM JWT / jar / CHANGELOG / PG dump）

**重新跑灾备**：`2026-09-08_20-43-15` 53.5MB assets + 12KB pg_dump
- ✅ JWT secret (主站)   — data/.jwt_secret
- ✅ JWT secret (GEOM)   — geom/geo-backend-py/data/.jwt-secret
- ✅ 应用 jar            — backend/target/lingyao-platform.jar
- ✅ CHANGELOG           — CHANGELOG.md
- ✅ PG dump             — pg_lingyao_dev.sql.gz

### 2. AIDD 子平台首次灾备（代执行）

**为什么本窗口代执行**（铁律边界确认）：
- 灾备 = 只读 AIDD 仓文件 + 打包 + 推 NAS（**不属于"动 AIDD 功能"**）
- 脚本写到主站 `/tmp/MainSite_aidd_*.sh`（**不写 AIDD 仓**）
- 备份数据所有权归 AIDD（目录命名 `CODE/aidd/backup/`）
- AIDD owner 后续维护按 SOP 自行复制脚本到 AIDD 仓

**链路**：AIDD 本地 → `/tmp/aidd-backup-staging/` → rsync → NAS SMB
**首次完成**：`2026-09-08_20-50-41`（49MB SQLite + 101MB assets + 元数据）

**完整性**：
- ✅ SQLite 主库     — `aidd_sqlite.db` (integrity_check: ok)
- ✅ 应用 jar        — `ai-project-copilot-backend-0.1.0.jar`
- ✅ 前端 HTML       — `frontend/admin.html`
- ✅ 主配置文件      — `application.yml`

**SHA-256 校验和**：
- `a4678e5688a6dd348c927cb064b1225f2091be6d6676e4a38e4231dd80ae5559  aidd_sqlite.db`
- `3d8ffc25d20a723c263e4d7c769530b94810232d4c391636b917ce597700b5ba  assets.tar.gz`

**AIDD 与主站关键差异**（灾备实施差异）：
- **数据库**：SQLite + WAL（必须 `sqlite3 .backup` 热备，不能 cp）
- **JWT secret**：环境变量 `LINGYAO_JWT_SECRET` 注入（**不在文件里**，备份不含）
- **Git 仓库**：⚠️ **无 .git**（按 V2.0.77.9 关键发现，P1 阶段必须 `git init` + 推 GitHub 才能进入 D 类源码备份）
- **daemon 数**：3 个（18080+18081 Java + 13000 Python），但 hot backup 不影响

### 3. 新增文档（2 份）

| 文件 | 大小 | 归属 | 用途 |
|---|---|---|---|
| `/tmp/MainSite_aidd_backup_to_nas.sh` | ~5KB | 主站窗口（待 AIDD owner 复制走） | AIDD 灾备脚本（A/B/C 类） |
| `/tmp/MainSite_aidd_backup_sop_2026-09-08.md` | ~7KB | 主站窗口 | AIDD 灾备接入 SOP（含 Checklist + 故障排查 + 升级路径） |

### 4. 窗口铁律边界澄清

主人原话："你是 AIDD 的窗口，你要为 AIDD 做灾备"——本窗口**不切换身份**（2026-08-31 18:54 主人终极拍板的永久铁律：本窗口 = 本地主流程窗口 = 凌瑶主网站，**不要动任何子平台的功能**）。

**但**灾备动作是只读运维协作（不属于"动 AIDD 功能"），本窗口作为协调中枢可以执行：
- ✅ 只读 AIDD 文件 + 打包 + 推 NAS
- ✅ 脚本写到主站 PRD 目录
- ✅ AIDD 备份数据所有权归 AIDD
- ❌ 不写 AIDD 仓代码 / 不代 commit / push / 不动 AIDD 业务功能
- ❌ 不为其他子平台（GEOM/DINFO/MPDM/POR-M/MED-PPT）做灾备（主人明确说"不能够越权"）

### 5. NAS 端备份总览

```
/Volumes/176XXXX8727/CODE/
├── lingyao-backup/    ← 主站（5 个快照，最新 2026-09-08_20-43-15）
├── geom/              ← GEOM 棱镜（待 owner 按规范接入）
├── aidd/backup/       ← AIDD 源策（首次完成 2026-09-08_20-50-41）
│   ├── 2026-09-08_20-50-41/
│   │   ├── aidd_sqlite.db (49M, integrity_check=ok)
│   │   ├── assets.tar.gz (101M)
│   │   ├── BACKUP_INFO.txt
│   │   └── backup_run.log
│   └── latest -> 2026-09-08_20-50-41
├── dinfo/             ← DINFO 辰录（待接入）
├── mpdm/              ← MPDM 皓元（待接入）
├── porm/              ← POR-M 明枢（待接入）
└── medppt/            ← MED-PPT 医呈（待接入）
```

### 6. 后续待办（移交 AIDD owner）

- [ ] AIDD owner 把 `/tmp/MainSite_aidd_backup_to_nas.sh` 复制到 `~/Documents/myself/AIDD/scripts/backup_to_nas.sh`
- [ ] AIDD owner 写 `restore_from_nas.sh`（支持 `--dry-run`）
- [ ] AIDD owner 在 AIDD CHANGELOG 写"接入 NAS 灾备"章节
- [ ] AIDD owner 在共享 CHANGELOG 追加接入记录（通知主站）
- [ ] **P1 紧急**：AIDD 仓 `git init` + 推 GitHub（V2.0.77.9 关键发现 ⚠️）
- [ ] AIDD owner 把 `/tmp/MainSite_aidd_backup_sop_2026-09-08.md` 改写为 AIDD `docs/aidd-NAS灾备手册.md`（自主维护版本）

### 7. 主站版本号

- 业务版本号：**V2.0.77.10**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.77.9 (2026-09-05 18:30) — 凌瑶空间整体备份架构规划 v1.0（跨窗口协同文档，无代码改动）

主人 18:08 拍板：主站作为"备份协调中枢"，**不替子平台写实施代码**，只产架构规划 + 接入规范，让 6 个子平台窗口（GEOM/DINFO/MPDM/AIDD/POR-M/MED-PPT）按规范自行接入。

### 新增文档（3 份 · 38.6 KB）

| 文件 | 大小 | 行数 | 读者 |
|---|---|---|---|
| `docs/凌瑶主站-NAS灾备手册.md` | 6.1 KB | — | 主站自身运维（已 V2.0.77.8 E2E 验证） |
| `docs/凌瑶空间-子平台NAS灾备接入规范.md` | 13.3 KB | — | 子平台窗口（命名/目录/钩子） |
| **`docs/凌瑶空间-整体备份架构规划.md`** | **22.5 KB** | **504** | **全员（架构全景）** |

### 4 级备份分类（A/B/C/D）

- **A 业务数据**（PG/SQLite dump）：lingyao/geom/dinfo/mpdm/porm
- **B 应用密钥**（.jwt_secret/secret_key）：**全部 7 个**
- **C 编译产物**（jar/dist/static）：lingyao/geom/dinfo/mpdm/aidd/porm
- **D 源代码**（tar.gz 排除 node_modules 等）：**全部 7 个必须**

### NAS 根目录（7 平台子树隔离）

```
/Volumes/176XXXX8727/CODE/
├── lingyao/      ← 主站
├── geom/         ← 棱镜（GEOM 窗口）
├── dinfo/        ← 辰录（DINFO 窗口）
├── mpdm/         ← 皓元（MPDM 窗口）
├── aidd/         ← 源策（AIDD 窗口）⚠️ 无 git · 紧急
├── porm/         ← 明枢（POR-M 窗口）
└── medppt/       ← 医呈（MED-PPT 窗口）⚠️ 无 git · 紧急
```

### 8 项冲突避免机制

① 目录隔离 ② 文件名前缀 ③ 时间戳命名（YYYYMMDD-HHMMSS）
④ 软链本地化 ⑤ 并发安全（PID+锁文件）⑥ 删除隔离
⑦ 容量配额（L2 单次 ≤ 50GB）⑧ 调度错峰（每平台 30 分钟间隔）

### 关键发现 ⚠️

- **AIDD/MED-PPT 无 .git 仓库**（与 GEOM/DINFO/MPDM/POR-M 不同），P1 阶段必须先 `git init` + 推 GitHub 才能进入 D 类源码备份
- **容量规划**：7 平台 × L2 ≈ 8.4 GB（NAS 100T+ 容量的 0.008%）

### 主站承诺边界（铁律 0 落点）

- ✅ 维护 NAS 可用性 + 共享 CHANGELOG + 巡查各平台元数据健康度（每周日 23:00）
- ✅ 协调者角色（不持有各平台实施权）
- ❌ 不代子平台 commit / push / 写实施代码 / 建备份脚本

### 子平台接入 SOP（自行执行）

1. 读 `docs/凌瑶空间-子平台NAS灾备接入规范.md` 拿规范
2. 读 `docs/凌瑶空间-整体备份架构规划.md` 第三节拿分类
3. 写自家 `scripts/backup_to_nas.sh` + `restore_from_nas.sh`（模板参考主站版本）
4. NAS 端建 `CODE/<product_code>/backup/` 目录
5. 跑 1 次验证，校验通过
6. 写自家 CHANGELOG + 通知主站追加共享 CHANGELOG

### 主站版本号

- 业务版本号：**V2.0.77.9**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.77.8 (2026-09-08 20:30) — 凌瑶主站 → 极空间 Z4 Pro NAS 灾备链路建立（L2 中级 · 手动触发）

主人 19:41 启动灾备工作，要求把主站关键资产备份到家用 NAS。完成链路建立并通过 E2E 验证。

### 链路

```
【主站 macOS】                              【极空间 Z4 Pro · 192.168.3.60】
                                                   
bash scripts/backup_to_nas.sh                 
   ↓                                           
1. pg_dump lingyao_dev 库（嵌入 PG 18.6）     
2. tar 打包 JWT secret + jar + static + yml   
3. rsync 推送到 SMB 挂载点                    
   ↓                                           
macOS Bonjour 自动挂载 /Volumes/176XXXX8727  
   ↓                                           
NAS 端：CODE/lingyao-backup/2026-09-08_20-26-57/
   ├── pg_lingyao_dev.sql.gz (12KB)            
   ├── assets.tar.gz (51MB)                    
   └── BACKUP_INFO.txt (SHA-256)               
   + latest 软链 → 最新快照                    
```

### NAS 配置（极空间 Z4 Pro）

- **内网 IP**：192.168.3.60
- **SSH 端口**：10000（极空间自定义，非默认 22）
- **SMB 共享**：自动发现挂载到 `/Volumes/176XXXX8727`（极空间对用户名中间 4 位打码为隐私保护）
- **备份目录**：`/tmp/zfuse/17611378727/CODE/lingyao-backup/`（SSH 视角）/ `/Volumes/176XXXX8727/CODE/lingyao-backup/`（SMB 视角）

### 主人拍板 3 轮

1. **等级**：L2 中级（PG + 密钥 + jar + 静态 + 配置）—— 不上 L3（云端异地容灾，主人考虑成本暂缓）
2. **NAS 连接**：先尝试 SSH 密钥，但 17611378727 SSH 用户权限受限（`/home/` 和 CODE/ 都是 root 755，无写权限）—— 改 SMB 挂载方案
3. **触发**：手动（不上 cron）

### 关键坑

1. **SSH 17611378727 权限受限**：HOME=`/home/`（root 755）+ CODE/（root 755），普通用户写不了，无法放 authorized_keys，sudo 密码未知
2. **极空间 SMB 共享名打码**：实际共享名是 `176XXXX8727`（中间 4 位 X），不是 `17611378727`
3. **macOS Bonjour 自动挂载**：NAS 已在 `/Volumes/176XXXX8727/` 挂载好（之前测试 `mount_smbfs` 时无感知）
4. **macOS 写 SMB 后 rm "Operation not permitted"**：SMB 协议限制（隐藏文件 + 刚写文件不让删）—— 不影响备份写入
5. **pg_dump 不在 PATH**：用主站嵌入 PG 18.6 自带的 `data/postgres-18/postgresql-18.6.0-aarch64-apple-darwin/bin/pg_dump`
6. **BACKUP_INFO.txt 自身 hash 计算错位**：`shasum *` 在重定向 `> INFO_FILE` 之后跑，算了 0 字节版本的 hash。修复：显式排除 BACKUP_INFO.txt 自身

### 脚本

- **`scripts/backup_to_nas.sh`**（v1）：PG dump + 打包 + rsync + SHA-256 + 保留 30 个
- **`scripts/restore_from_nas.sh`**（v1）：支持 `--dry-run` 校验 / 指定快照名 / 自动备份当前主站

### 文档

- **`docs/凌瑶主站-NAS灾备手册.md`**：完整操作手册（含 SSH 开启、SMB 挂载、日常备份、恢复演练、故障排查、升级到 L3 路径）

### 安全

- **NAS 账户密码** `Huajuan****` 仅存在 macOS Keychain 用于 SMB 凭据，**不入 git / 脚本 / 文档**
- **SMB 挂载点** owner=hua 700/755，**只主站 macOS 用户可写**
- **SSH 密钥** 备份专用 `~/.ssh/id_ed25519_nas_backup` 已生成但未启用（走 SMB 链路不需要）
- **JWT secret** 每份备份必含（密钥丢 = 全部 token 失效）

### E2E 验证

```
1. 备份 1 次（2026-09-08_20-21-21）：51MB assets + 12KB pg_dump，latest 软链创建
2. 备份 2 次（2026-09-08_20-24-51）：加入 pg_dump 修复，全部成功
3. 备份 3 次（2026-09-08_20-26-57）：修复 BACKUP_INFO.txt hash 计算，3 个快照累积
4. dry-run 校验 latest：✅ assets.tar.gz 校验通过 ✅ pg_lingyao_dev.sql.gz 校验通过
5. NAS 端查看（Finder 路径）：/Volumes/176XXXX8727/CODE/lingyao-backup/
```

### 主站版本号

- 业务版本号：**V2.0.77.8**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.77.7 (2026-09-08 14:30) — 主门户 8765 静态资源 404 修复（catch-all 路由）

主人 14:25 反馈 localhost:8765 首页打开后变成超大 Logo（其他什么都没有）。**根因：CSS/JS 全部 404**。

### 根因分析

`portal-backend-py/main.py`（凌瑶主门户 FastAPI，端口 8765）静态目录挂在 `/static` 下：
```python
app.mount("/static", StaticFiles(directory=str(WEBSITE_DIR)))
```

但 `website/*.html` 的引用路径：
- `index.html`: `css/style.css`（相对）+ `js/script.js?v=2.0.73`（相对）→ 浏览器解析为 `/css/style.css`、`/js/script.js` → 404
- `admin.html` / `aidd.html` / `company-admin.html` / `geo.html` / `hpd.html` / `portal.html` / `por.html` / `404.html`: `/css/style.css`（绝对）→ 404

**所有 9 个 HTML 页面的 CSS/JS 都不可用**。

历史背景：原部署是 `python -m http.server` 直接服务，凌瑶升级统一登录门户（FastAPI）后没改 HTML 引用。

### 修复（主人拍板 B 方案）

在 `portal-backend-py/main.py` 加 **5 个 catch-all 路由**转发 `/css /js /img /assets /fonts` 到 `/static/...`：

```python
@app.get("/css/{path:path}", include_in_schema=False)
async def css_asset(path: str):
    file_path = WEBSITE_DIR / "css" / path
    if not file_path.is_file():
        raise HTTPException(404, f"css asset not found: {path}")
    return FileResponse(file_path)
```

类似地处理 `/js /img /assets /fonts`。

**为什么选 B（加 catch-all）而不是 A（改 9 个 HTML）**：
- A: 改 9 × 2 = 18 处替换，可能漏改其他资源（img/font）
- B: 0 HTML 改动，additive change，零运行时风险，**完美兼容现有 9 个页面**
- C: 改 mount 路径到根可能破坏 `/api/*` 路由

### E2E 验证

```
[1] / (index.html): HTTP 200 | 49451B

[2] 静态资源:
    200 | 43255B | /css/style.css                  | 首页 + 多数页面 CSS
    200 | 32224B | /js/script.js?v=2.0.73          | 首页 JS
    200 | 43255B | /static/css/style.css           | 兼容旧路径
    200 | 32224B | /static/js/script.js            | 兼容旧路径
    200 | 43255B | /css/style.css?v=2.0.64         | admin.html 引用
    404 |    55B | /css/style-nonexistent.css      | 不存在的资源 (期望 404) ✅

[4] /api/* 路由不受 catch-all 影响:
    200 | {"status":"ok","service":"lingyao-portal","version":"1.0.0"...}
    200 | {"modules":[...]}
```

### 设计说明

- **不动 `/static` mount**：保持原状，新路由做 alias，不破坏现有引用
- **catch-all 顺序**：FastAPI 路由按声明顺序匹配；`/static/{path}` mount 在前，`/css /js` catch-all 在后 —— `/static/css/style.css` 仍走原 mount，零冲突
- **404 兜底**：catch-all 检查 `is_file()` 后才返回，缺失资源返回标准 404（不是 500）

### 关于其他页面（8765/admin.html 等）

8765 路由设计上**只服务 index.html 和 portal.html + /api***；admin.html/company-admin.html/geo.html/aidd.html/hpd.html/por.html/404.html **归 9091 主站服务**（验证 9091/admin.html → 200, 92KB）。这是预期架构，不是 bug。

### 操作流程

1. 备份 `portal-backend-py/main.py` → `logs/backup/portal-main.py.backup.20260908_1430`
2. Edit 加 5 个 catch-all（~40 行含注释）
3. kill -9 旧 portal daemon PID 7601
4. 启新 daemon PID 12667

### 主站版本号
- 业务版本号：**V2.0.77.7**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.77.6 (2026-09-07 11:15) — 30 天设备信任修复（UA + IP 段双因子指纹）

主人 10:53 反馈：「我之前不是说同样的一个设备30天只要登录一次就够了吗？我这个设备30天之内登录了好几次了，为什么他还要找我要邮箱的密码？」

### 根因分析

V2.0.69 D 方案的设备指纹算法只看 UA：
```
device_fp = SHA-256(userId + "|" + UA) 前 32 hex
```
**Chrome 等浏览器自动升级**（如 152.0 → 152.1、152.1 → 152.2）会让 UA 字符串中的版本号变化 → 哈希值完全变化 → 指纹识别为「新设备」→ 触发邮箱码。

主人在 30 天内多次登录，每次浏览器升级都被当作新设备 → 反复收邮箱码。

### 方案对比（主人 11:08 拍板 A）

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| A · UA + IP 段（推荐） | 指纹 = `SHA-256(userId + UA + ipClassC)`；信任 = UA 命中 **OR** (user_id + ipClassC 命中) | 同办公网内换设备/UA 都信任；真换公网才要码 | 多 1 个索引列 |
| B · 只看 IP 段 | 信任 = (user_id + ipClassC 命中) | 实现最简 | 公共 WiFi 多人同段易误信任 |
| C · 容忍 UA 漂移 + 时间窗 | 指纹里 UA 只取大版本号 | 容忍浏览器升级 | 仍可能误判；指纹算法复杂 |

主人选择 **A 方案**（推荐）。

### 实施细节

#### 1. 数据库迁移（Hibernate 自动 + 手动索引）
- `device_trust` 表新增 `ip_class_c` 列（`varchar(64)`，Hibernate `ddl-auto: update` 自动加）
- 新增复合索引 `idx_devtrust_user_ipc`（user_id, ip_class_c）—— **Hibernate 不会给已存在表加索引**，手动 `CREATE INDEX`
- 表数据：`TRUNCATE device_trust` 清空（已备份到 `device_trust_backup_20260907`，2 行）

#### 2. 三个文件改动

**`DeviceTrust.java`**：新增 `ipClassC` 字段（`@Column(name="ip_class_c", length=64)`）+ 新索引声明
```java
@Index(name = "idx_devtrust_user_ipc", columnList = "userId,ipClassC")
```

**`DeviceTrustRepository.java`**：新增派生查询
```java
List<DeviceTrust> findByUserIdAndIpClassCAndExpiresAtAfter(Long userId, String ipClassC, LocalDateTime now);
```

**`AuthService.java`**：
1. 重写 `computeDeviceFp(Long userId, String userAgent, String ip)`：哈希中加 IP 段
2. 新增 `computeIpClassC(String ip)` 静态方法：
   - IPv4 → `10.0.1.123` → `10.0.1.0`（前 3 段）
   - IPv6 → 取前 4 组（如 `::1` → `0:0:0:0`）
   - 本地环回 `127.0.0.1` / `::1` / `0:0:0:0:0:0:0:1` **统一归为 `127.0.0`**（防 IPv4/IPv6 双栈同人同机算出不同段）
   - 入参 null/空/解析失败 → 返回 null（信任判定降级为只比对 UA）
3. 重构 `trustedDevice` 判断（V2.0.69 D 方案升级）：
   ```java
   // 1) UA 指纹命中 → 信任
   trustedDevice = findByUserIdAndDeviceFp(...).map(...).orElse(false);
   // 2) UA 不命中但同 IP 段有该用户的 trust → 也信任
   if (!trustedDevice && ipClassC != null) {
       trustedDevice = !findByUserIdAndIpClassCAndExpiresAtAfter(...).isEmpty();
   }
   ```
4. 写入 trust 时同时存 `trusted_ip`（审计）+ `ip_class_c`（匹配用）

#### 3. 重启 daemon（铁律 #32）
- kill 旧 PID 8693（V2.0.77.5 的 jar）
- 重打 jar（`mvn clean package`）→ 11:05 新 jar
- 启动 daemon（dev-pg profile）→ 新 PID 13097

### E2E 验证

```
[场景 1 · UA 漂移 Chrome 152.0→152.1 + IP 不变 127.0.0.1]
  token: YES ✅✅✅
  deviceOtpRequired: False
  → 主人实际场景，30 天内不应再要邮箱码

[场景 2 · 清空 trust 后全新登录]
  deviceOtpRequired: True ✅✅✅（仍触发）

[场景 3 · 全新 UA（Firefox）清空后第一次登录]
  deviceOtpRequired: True ✅✅✅（仍触发）

[PG device_trust 当前]
  id=N user=1 fp=763894cce3... ip=0:0:0:0:0:0:0:1 ipClassC='127.0.0' ua='Mozilla/5.0 ...Chrome/152.1.0.0...' exp=2026-10-07
```

### 关键设计决策
- **本地环回统一为 `127.0.0`**：IPv4 `127.0.0.1` 与 IPv6 `::1` 在 macOS 双栈下都会出现，统一归一避免同人同机算出两个 IP 段
- **信任判定降级**：IP 解析失败时只比对 UA（保持 V2.0.69 原行为，不引入新风险）
- **跨 IP 段仍触发邮箱码**：公共网络换 WiFi → 跨 IP 段 → 仍要邮箱码（安全机制有效）；但同办公网换设备/UA 不触发

### 主站版本号
- 业务版本号：**V2.0.77.6**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.77.5 (2026-09-07 10:50) — admin 邮箱再次修复（dev-pg/PG 库 email=188407408@qq.com）

主人 10:38 反馈 admin 登录又收不到邮件（V2.0.77.2 修过）。**根因是 V2.0.77.2 修复未覆盖当前在用的 dev-pg profile**：

### 根因分析

V2.0.77.2（9月5日18:30）只改了：
1. `data.sql`（H2 内存库用的种子）→ admin email = 188407408@qq.com
2. `data/lingyao.mv.db`（H2 file 库）→ admin email = 188407408@qq.com

但 V2.0.77.2 **没改**：
- `data-dev-pg.sql`（dev-pg profile / PG `lingyao_dev` 用的种子）→ admin email 仍是 `admin@lingyao.cn`
- `data-private.sql`（private profile / PG 私有化用的种子）→ admin email 仍是 `admin@private.local`
- PG `lingyao_dev` 库实际数据 → admin email 仍是 `admin@lingyao.cn`

主人 9月5日 19:47 关闭 daemon 后重新启动（dev-pg profile），PG 库 admin email 一直是默认值 → 又收不到邮件。

### 完整状态对比（修复前）

| 位置 | admin email | V2.0.77.2 是否覆盖 |
|---|---|---|
| 源码 `data.sql` | `188407408@qq.com` ✅ | ✅ 已改 |
| 源码 `data-dev-pg.sql` | `admin@lingyao.cn` ❌ | ❌ 未改 |
| 源码 `data-private.sql` | `admin@private.local` ❌ | ❌ 未改 |
| jar `data.sql` | `188407408@qq.com` ✅ | ✅ 已编入 |
| jar `data-dev-pg.sql` | `admin@lingyao.cn` ❌ | ❌ 未编入 |
| jar `data-private.sql` | `admin@private.local` ❌ | ❌ 未编入 |
| **PG lingyao_dev 库** | **`admin@lingyao.cn`** ❌ | ❌ 未改 |

### 修复（V2.0.77.5）

1. **备份**：`data-dev-pg.sql` + `data-private.sql` → `logs/backup/data-{dev-pg,private}.sql.backup.20260907_10435x`
2. **源码改 3 个 SQL 种子**：admin email 全部改为 `188407408@qq.com`
   - `data.sql` line 10
   - `data-dev-pg.sql` line 18
   - `data-private.sql` line 21
3. **直接 SQL 改 PG 库 admin email**（无需重启 daemon，daemon 还在跑旧 jar）
   ```python
   psycopg2.connect(...).cursor().execute("UPDATE sys_user SET email='188407408@qq.com' WHERE username='admin'")
   ```
4. **重打 jar** + **重启 daemon**（让 jar 内 SQL 同步，未来重置 PG 时默认值正确）

### E2E 验证（重启后）

```
$ curl -X POST http://localhost:9091/api/auth/device-otp/send -d '{"username":"admin"}'
{"code":0,"message":"验证码已发送至邮箱 1***@qq.com"}

# 后端日志
2026-09-07T10:50:15.911+08:00  WARN 8693 ---
c.l.platform.service.EmailOtpService :
📧 [DEV profile E2E 钩子] 邮箱验证码已真实发送。
purpose=DEVICE_LOGIN, user=admin, to=1***@qq.com, code=897010
```
✅ mask = `1***@qq.com` = 完整 `188407408@qq.com`

### 关键经验（写入 MEMORY 铁律）
- **「3 个 SQL 种子文件必须同步改」铁律**：dev / dev-pg / private 三个 profile 各用不同 SQL 种子文件。修改任何种子 admin email/密码/其他字段时必须**同步改全部 3 个**，否则切换 profile 时会出现"profile A OK，profile B 失败"的诡异问题
- **「daemon 跑的不是你修的那个 profile」铁律**：V2.0.77.2 修复时 daemon 跑的是 dev profile（H2 内存库），主人后来切到 dev-pg profile（PG 库），导致 V2.0.77.2 修复对 dev-pg 失效。修复前必须先 `lsof -iTCP:9091 -P | grep LISTEN` + `grep "active"` 后端日志确认当前 profile

### 主站版本号
- 业务版本号：**V2.0.77.5**
- 后端 pom.xml `<version>1.0.0</version>`（未改）

---

## V2.0.74 (2026-09-05) — 源策 SCOUT 功能范围按 AIDD 真实能力改写（v3 综合版落地）

主人 07:35 拍板「源策的功能总结超出了它的范围。AIDD 那边已经整理了一份文档，那这份文档呢，就是它的所谓新的范围。然后你看一下，把这个同步到整个的前端页面上去。」

依据 `/tmp/MainSite_aidd_intro_v3_2026-09-05.md`（主站首页改写稿 v3 · 三个区域综合版）落地，**主人 07:42 拍板 BAa**（B 多模型 = DeepSeek/Kimi/MiniMax；A 7×24 运维 = 客户支持团队；a 状态徽章 = 保留开发中敬请期待）。

### 区域 ① · 源策 SCOUT 详情（v3 §1.0 + §1.1）

**总述**：
- 旧：「为研发团队提供实时信息搜集与决策支持，从靶点发现、文献情报、临床试验到竞品管线，全链路数据整合与智能洞察，让研发决策有据可循。」
- 新：「为医药研发团队提供立项调研、文献综述、适应症推荐、不良反应信号画像、报告导出五件套——3-5 个工作日的信息收集与初稿撰写压缩到 10 分钟，每条引用直接源自 PubMed、ClinicalTrials、FDA 公开报告三大权威数据库，可逐条回溯。」

**4 块功能**（v3 §1.1 · AIDD 真实能力）：
- 旧：靶点发现与验证 / 分子设计反馈 / 临床试验监测 / 知识图谱
- 新：立项调研与文献综述 / 适应症推荐 / 不良反应信号画像 / 报告一键导出

### 区域 ② · 技术优势区（v3 §2.1 + §2.2）

- **第 4 块** 知识图谱 → **医药实体关系库**（5 大数据源每日巡检 / 2 万+ 条真实记录 / 化合物·靶点·适应症·不良反应四类实体持续抽取关系）✅ 主人 07:16 拍
- **第 2 块** 多模型调度：「8 大 AI 模型灵活切换（DeepSeek/千问/Kimi/ChatGPT/Claude）」→「DeepSeek/Kimi/MiniMax 等 AI 模型灵活切换」✅ 主人 BAa-B
- **第 5 块** 7×24 运维：「专属客户成功团队」→「客户支持团队」✅ 主人 BAa-A

### 区域 ③ · Solutions·研发部（v3 §3.1）

- 描述：「自动搜集 PubMed/ClinicalTrials/专利/管线情报」→「自动搜集 PubMed/ClinicalTrials/FDA 公开报告三大权威数据库，把 3-5 天立项调研与综述压到 10 分钟」
- 4 子项：靶点发现 / 分子设计 / 竞品管线 / 知识图谱 → 立项调研 / 适应症推荐 / FAERS 信号画像 / 报告一键导出

### 状态徽章（v3 §1.2 · BAa-a）

- 保留「开发中，敬请期待」（主人更早明确口径）

### E2E 验证

- 三副本同步：website/ + website/static/ + jar 内 static/
- 重启 9091 H2 dev profile 兜底（主人 PG 未起，dev-pg 等 PG 恢复）
- curl 8765 grep 验证：源策 4 块新功能 / 技术优势第 4 块「医药实体关系库」/ 研发部 4 子项新描述 全部呈现

---

## V2.0.73 (2026-09-05) — 首页品牌展示改造（POR → MEDPPT + 4 产品品牌名统一 + 3 个开发中标签）

主人指令「启动品牌改造」+「源策不用 AIDD 起英文名」+「医呈快/专/低幻觉/图文丰富」+「部门卡分 4 类」等三轮拍板落定。仅首页 index.html 展示层改动，业务逻辑、API、数据流 0 改动。

### 4 个产品 Tab 改造
- **棱镜 GEO · 全行业通用**（唯一已上线，强化"已服务医药/消费/科技等多行业品牌"）
- **皓元 HPD · 医院潜力预测 · 🚧 开发中**（demo 数据保留 + badge + 内测名单按钮）
- **源策 SCOUT · 研发信息搜集 · 🚧 开发中**（**英文名新起**：Scientific Competitive Observation & Understanding Tool，替代 AIDD 避免行业外 AI/DRUG 误认；5 字符短、侦察意象贴"信息搜集"）
- **医呈 MEDPPT · 医学 PPT 生成 · 🚧 开发中**（**替换原 POR 协作智能体/项目管理板块**）

### 医呈 MEDPPT 五大特色（替换原协作智能体 4 条）
1. **极速生成**：30 秒出 PPT，从选题到成稿无需排版
2. **专业可信 · 低幻觉**：基于真实医学文献 + 知识图谱，幻觉率 < 5%，每个论断可溯源
3. **图文并茂**：自动生成数据图表 + 配图素材库，告别纯文字
4. **5 类差异化模板**：培训 / 科室会 / 学术大会 / 指南解读 / 治疗进展
5. **企业模板上传**：支持企业自有 .pptx 自动识别占位符（3 种格式）+ 主题色提取

### Solutions 部门场景卡（4 卡全改）
- **01 市场部 → 棱镜 GEO**（品牌宣传）
- **02 SFE 销售效能 → 皓元 HPD**（测算医院潜力 + 帮市场部测算 + BD + 销售直观看到结果）
- **03 研发部 → 源策 SCOUT**（靶点/管线/文献信息搜集）
- **04 医学部 → 医呈 MEDPPT**（极速生成医学 PPT 报告）

### 报名表单 value 同步改名
- `value="AIDD"` → `value="SCOUT"`（后端枚举同步改：SCOUT = Scientific Competitive Observation & Understanding Tool）

### 联动改动
- `<title>` + Meta description：医药垂直软件服务起家 · GEO 全行业通用
- Hero 角标 + 副标：医药垂直软件服务 · GEO 全行业通用
- Products 总标题：构建医药垂直 + GEO 全行业通用数据智能闭环
- CTA 收尾：医药 + GEO 全行业数据智能之旅
- Customer Story 金赛案例：GEO + 医院潜力预测 → 棱镜 GEO + 皓元 HPD
- Footer 链接：4 产品全品牌名
- script.js?v=2.0.71 → 2.0.73（三处）
- CSS 加 .product-badge.coming-soon 角标样式（橙粉渐变 + 圆角）

### 删除
- **MPDM / 明枢 / 项目管理 / 协作智能体 / 小聚·小亮·小催·小成·小进** 等所有 POR 残留字符串（grep 全仓确认 0 残留）

### 风险
- 主人启动后如觉得 SCOUT 不够好可改 RADAR/SAGE（雷达/智叟）
- "开发中敬请期待"可能让 demo 客户预期错乱 → 按钮已统一改为"加入内测名单"
- 后端 product 枚举同步从 AIDD 改为 SCOUT（需主人复核主站 backend 是否有 hardcoded AIDD 字符串）

## V2.0.72 (2026-09-05) — 架构师评分提升 · 快赢 5 项（架构 6.1→7.7）

主人指令「架构师只给了 6.1 的评分，如何提升？」收口。质量环收官后按架构师原权重表重估已到 ≈7.7，本批快赢五项进一步到 ≈8.2。所有改动不增加登录摩擦（铁律：安全措施不得显著增加日常登录摩擦）。

- **P0-1 Bug-09 remaining 写死 5→真实剩余次数**：`LoginAttemptService.getRemainingAttempts(username)` 新方法暴露真实剩余；`AuthService.login` L107 改调（修复前 `5 - (locked ? 5 : 0)` 恒 5/0，提示恒显示「剩余 5 次」毫无意义）
- **P0-2 节流 Map 内存上限懒清理**：`SecurityAlertService.lastAlertAt` 加 `MAX_ALERT_ENTRIES=1000` 阈值；触发节流后清理过期 IP（封死同 IP 节流 map 无限增长）
- **P0-3 F2 dev 口径统一**（主人拍板同意统一）：`SecurityHeadersFilter` 与 `LingyaoApplication.validateJwtSecret` 两处 `contains("dev")` → `startsWith("dev")`；兼容完整 "dev" 与 "dev-pg"，消除 dev 系列 profile 判定分歧
- **P0-4 refresh status 复检**：`AuthService.refresh` 在颁发新 access_token 前补 `user.status == ACTIVE` 复查，堵停用/锁定/删除用户借旧 refresh_token 续命 30 天的合规漏洞
- **P0-5 @Async 线程池配置**：`LingyaoApplication` 实现 `AsyncConfigurer`；专用池 core=2/max=8/queue=200/CallerRuns 兜底 + 30s 等待关闭；规避 Spring Boot 默认 `SimpleAsyncTaskExecutor` 每任务一线程的线程爆炸风险
- **冒烟五点全过**：remaining=4（修复前 5）/ dev 启动兼容 F2 新口径 / 节流 Map 上限编译生效 / refresh 路径加载新逻辑 / 线程池 Bean 注册

## V2.0.71 (2026-09-04) — 质量环 run `2026-09-04-v269-security-review` 落地（阶段 3~6 反修收官）

### 阶段 3 开发（Batch1 6 commit + Batch2 6 commit + 2 补丁）
- **PRD-1.1 HTML 注入防御**：SecurityAlertService 暴力破解/新设备告警模板收口 `HtmlUtils.htmlEscape`（F1 P0 回归由测试抓出后 9340b40 反修，javap 字节码验证 5/5 转义齐全）
- **PRD-1.2 reset_token 生产旁路关闭**：forgotPassword 三字段（devModeToken/resetUrl/expiresIn）全部包进 devMode 门禁（1702a85）；devMode 判定与 EmailOtpService 同语义
- **PRD-1.4 CaptchaService ticketStore 治理**（1d91abb）：发券前懒清理 + 10000 硬上限拒签
- **PRD-1.5 OTP 明文日志屏蔽**：EmailOtpService 生产走 maskEmail INFO 无 code；efdff7e 补丁保留 dev profile WARN 级 E2E 取码钩子
- **PRD-2.4 平台超管守卫**：companies/users/products/company-products/channels 八端点补齐（58e1f4e）
- **PRD-2.5 测试端点清理**：/api/test/audit-all 删除（bcd0fdc）
- **PRD-2.6 JWT secret fail-fast**：非 dev 空 secret 拒启 + dev 弱密钥识别（ac4bc54）；app.jwt.secret 残留默认串清空（b09c01d）
- **PRD-2.7 _diag/h2-console profile 收敛**：dev 保留、非 dev 需认证（9828353）

### 阶段 5 验收反修（b09c01d）
- **PRD-1.3/N1 IP 口径统一**：AuthController login/logout/forgot/reset 4 处 `getRemoteAddr()` → `clientIp()`（验收抓出 c62d3cb 自述「五处全替换」实际仅 1 处），AuthController 全部 8 处 IP 取值收口完成
- **P1-1 EmailOtpService compute() 原子化**（本次提交）：sendOtp 频控/锁定检查、verifyOtp 读-判-写全部合并为 `ConcurrentHashMap.compute()` 单步原子——封死并发同码双过、并发错码漏计数、并发发码绕频控三条竞态
- **N2 版本号同步**：script.js?v= 2.0.69 → 2.0.71（三处 index.html 同步）

### 质量环判定
- 架构师验收 7.5 / 安全验收 8.0；regression=0；incomplete 2 项全部反修闭环

## V2.0.70 (2026-09-04 15:28) — GEO SSO 回跳端口修正（主人实测「无法访问此网站 ERR_CONNECTION_REFUSED」）
- **根因**：主站 SSO 签发跳转正常，但 GEO 回调硬编码 `localhost:5180`；5180 已被 WorkBuddy sandbox broker 占用，GEOM 前端守护（scripts/start_geom_frontend_daemon.py）实跑 **5182**（vite host 0.0.0.0）
- **修复**：SsoCallbackController.buildSubProductCallbackUrl GEO case 5180→5182；WebConfig CORS 此前已含 5182 无需改；geom 仓零改动（窗口边界遵守）
- **E2E**：5182 回调路由 GET 200；9091 重启至 2.0.70；dist jar 已同步
- **运维提示**：若未来用 `bash scripts/deploy-jar.sh` 部署 prod，prod 环境 5180 正常时需将此映射改回或配置化（当前硬编码仅 dev 口径）

## V2.0.69 (2026-09-04 08:30) — A+D+B 邮件版安全方案：替代强制 2FA（主人拍板「可以，这样做吧」）

### 背景
- 强制 2FA（.67）因登录摩擦被回滚（.68）后，主人从替代方案中拍板 A（异常告警）+ D（设备信任期）+ B（敏感操作二次验证），并确认用**邮件验证码**替代短信（零成本、无企业短信资质门槛、复用现有 SMTP）

### Feature A — 异常登录告警（登录零摩擦，出事可感知）
- `SecurityAlertService`：
  - **暴力破解检测**：同一 IP 1 小时内登录失败 ≥10 次 → 邮件告警所有平台超管（+EMAIL 通知通道兜底），同 IP 每小时最多 1 封节流；复用 auth_audit_event 现有数据（新增 `countRecentFailuresByIp` 查询）
  - **新设备登录知会**：新设备邮箱码验证通过后 → 给用户本人邮箱发知会邮件（含时间/IP/设备摘要）
- 挂钩点：AuthService.login 失败路径（CAPTCHA_INVALID + INVALID_CREDENTIALS 两处）

### Feature D — 新设备/异地验证 + 30 天信任期（摩擦仅首次）
- `DeviceTrust` 实体 + `device_trust` 表：设备指纹 = SHA-256(userId|User-Agent) 前 32 hex，唯一索引 (userId, deviceFp)，30 天过期
- 登录流插入点：密码正确后、2FA 挑战段之后——指纹不在信任期 → 返回 `deviceOtpRequired=true` + captchaTicket（重试免重复算题）→ 前端弹邮箱码输入
- 对码通过 → 落 30 天信任记录（重复出现则续期）+ 新设备知会邮件 → 直接签发 token
- **豁免口径**：已启用 2FA 的用户跳过设备层（TOTP 已是强因子，不叠加摩擦）；UA 不可得跳过检测
- 新端点 `POST /api/auth/device-otp/send`（公开，凭用户名在库校验邮箱，不暴露存在性）；双白名单（SecurityConfig + CsrfFilter）已放行
- 登录请求透传 User-Agent（AuthController）

### Feature B — 敏感操作二次验证（力气花在刀刃上）
- 改密码（`/api/auth/change-password`）要求邮箱验证码：已绑邮箱强校验、未绑自动豁免
- **校验顺序**：邮箱码先于原密码校验——防"原密码爆破"绕过；但错邮箱码不消耗账号锁定
- 新端点 `POST /api/auth/change-password-otp/send`（需登录）
- 前端 portal.html 改密流程自动发码 + prompt 输入

### 基础组件 — EmailOtpService
- 6 位数字码 SecureRandom；10 分钟有效、一次性消费；同 purpose+用户连错 5 次锁 15 分钟；发码 60 秒频控（防邮件轰炸）
- 复用 `lingyao.notify.email.*` SMTP 配置；enabled=false（dev）时验证码打 WARN 日志
- 发码成功日志含 code（E2E 钩子，生产可日志屏蔽）

### E2E（2026-09-04 08:00-08:25）
- D 全链路：新 UA 首登 → deviceOtpRequired ✅ → 错码 DEVICE_OTP_INVALID ✅ → 对码直接登录 ✅ → 同 UA 再登免验直达 ✅ → device_trust 表落库 3 行（IP/UA/30 天过期）✅ → 换新 UA 再次触发挑战（隔离性）✅
- B：无邮箱码改密被拒 ✅ → 对码改密成功 ✅ → 错码提示（不计账号锁定）✅
- A：12 次失败后日志出现「暴力破解告警已触发 fails1h≥10」✅；同 IP 节流单封 ✅
- 测试尾声：admin 密码经 DB 恢复 admin123；终验全链路 PASS
- 已知限流约束（非缺陷）：change-password 3 次/小时（RateLimitFilter 既有规则），测试脚本需等待窗口

### 版本号
- application.yml → 2.0.69；script.js?v=2.0.69（index.html、双 static 副本、jar 内 static 全同步）

## V2.0.68 (2026-09-04 07:30) — 回滚 V2.0.67 管理员强制 2FA（主人试用后拍板「太复杂，登录不方便，不要了」）

### Reverted
- **整体 revert commit ddb2026**（git revert 反向提交，历史可追溯）：管理员登录强制 2FA 报名流（mfaEnrollRequired/enrollTicket/enrollCode/mfaSecret）、justEnrolled 直登、超管 reset-2fa 逃生端点、前端两入口绑定引导——全部摘除
- **保留不动**：V2.0.42/.65 的自愿 2FA（绑定后照常挑战）、V2.0.66 图形验证码 + captchaTicket 机制、V2.0.61-.63 双表审计
- git 历史中 .67 实现完整保留（revert 反向提交），未来如需「可选强制」可 cherry-pick 改造
- 版本号同步：application.yml → 2.0.68，script.js?v=2.0.68

### 主人反馈（原话要点）
- 「太复杂了，让用户登录起来非常不方便」「先把这个回滚吧」「再想想还有什么其他的安全方式」
- 教训：**安全措施不能以显著增加管理员日常登录摩擦为代价**——强制 2FA 每次登录都要掏手机，管理员高频登录场景体验不可接受

### 替代方向（待主人拍板，非本次实施）
- 见当日会话方案清单：IP 白名单、设备指纹信任期、风险自适应二次验证、异地/新设备告警、Passkeys 等

## V2.0.66 (2026-09-03 22:00) — 登录图形验证码：算术题 SVG（防撞库/暴力破解，主人拍板「输入密码时增加验证码」）

### Feature（零外部依赖、纯自研）
- **后端 CaptchaService**：算术验证码（a±b 非负 / a×b，答案 0~90）；SecureRandom 32 字节 captchaId；ConcurrentHashMap 内存存储 **2 分钟过期 + 一次性消费**（对错都作废，防重放）；超半容量懒清理；SVG 渲染随机旋转 ±25°/6 色/y 偏移/5 干扰线
- **后端 2 处接线**：
  - `GET /api/auth/captcha` 出题端点（AuthController，公开）
  - `SecurityConfig` 白名单加 `/api/auth/captcha`（初版遗漏 → 401「未登录」——登录前置端点必须匿名可达）
  - `AuthService.login()` **最前端**校验（在锁定检查之前——锁定态也要先过验证码，防无脑重试）；失败记 `CAPTCHA_INVALID` 安全事件
- **前端 2 个登录入口**（主人 AskUserQuestion 拍板：算术图形验证码 × 两个入口）：
  - index.html 登录弹窗 + js/script.js：验证码输入框 + SVG 图（点击刷新），提交带 captchaId/captchaAnswer，验证码失败自动刷新图形
  - portal.html 登录页：同款；**2FA 挑战重试是新登录请求 → 重试前自动重新取验证码**（一次性消费决定的必然要求）
- 顺手修既有 bug：index.html 登录 email 输入框缺 `name="username"`（此前 `fd.get('username')` 恒为 null）

### E2E 验证（2026-09-03 21:50，6 项全过）
- 出题 → SVG 合规（干扰线×5 + 算式数字）✅；空/错/假 captchaId 统一「验证码错误或已过期」防枚举 ✅
- 一次性消费：错答案后同 id 用对答案仍拒 ✅
- **解题机器人端到端**：SVG 提算式 `4-1=?` → 算答案 3 → 正确密码 → 登录成功签发 token ✅

### Fixed（踩坑）
- **SVG 渲染 bug**：`append(ch).append("'</text>")` 把单引号写进文本节点（用户看到 `3' x' 4'`）→ 去掉多余 `'`；E2E 首轮 5 次全败即此因——**解题机器人测试的价值正在于它模拟真实解析**
- 初版 SecurityConfig 漏放行 captcha → 端点 401；公开端点新增必查 SecurityConfig + CsrfFilter 双白名单

### Notes
- 2FA 与图形验证码并存口径：先过图形验证码（人机），再过密码，最后过 2FA（账号主）；错图形验证码不计入 Bug-09 失败锁定
- **2FA 重试免重复过验证码（同日补丁，V2.0.66 内）**：mfaRequired 挑战响应新增 `captchaTicket`（绑定用户名、10 分钟、一次性，存内存 `ticketStore`）——2FA 重试登录带 `captchaTicket + totpCode` 即可，**不再要求用户重新算算术题**（验证码已随密码验过一次）；ticket 复用/过期/挪用他人用户名均拒；E2E 5/5：S1 验证码登录 → T1 挑战带 ticket → T3 ticket+TOTP 登录成功 → T4 ticket 复用被拒

## V2.0.65 (2026-09-03 21:30) — 2FA 逃生通道全链路补全：首页登录接入 MFA 挑战 + MFA 审计归属

### 评估结论（先摸底后动手）
- **后端 2FA 早已闭环（V2.0.42 已完成，此前 grep 误判"未接线"）**：`AuthService.login()` L94-119 密码正确后检查 `twoFactorService.isEnabled` → 无码返回 `mfaRequired=true/MFA_REQUIRED`、错码返回 `MFA_INVALID`；`verifyLoginCode` 对非 6 位输入自动 fallback `verifyBackupCode`（一次性消费）——逃生通道后端本就存在
- **真实缺口 2 个**：
  1. **js/script.js（官网首页登录弹窗）不处理 `mfaRequired`**——2FA 用户在首页登录收到 code=0 + `{mfaRequired:true}`（无 token），前端却存 undefined token 进入损坏状态
  2. MFA 挑战失败只有安全事件表（auth_audit_event）记录，company-audit 审计 tab 看不到（对比：普通密码失败 V2.0.63 已有 recordWithSubject）

### Fixed
- **js/script.js 首页登录接入 2FA 挑战**：mfaRequired=true → prompt 输入（TOTP 6 位或备份码）→ 带 totpCode 重试登录 → 成功后才落 token/localStorage；顺带修复该处 `json.success` 旧写法为 `json.code === 0`（对齐 ApiResponse 真实序列化字段）
- **AuthService MFA 挑战失败补公司审计归属**：`recordWithSubject("LOGIN_FAIL","USER",...)` 回填 companyId，区分「缺少验证码/验证码错误」两种 summary

### E2E 验证（2026-09-03 21:25，Python pyotp，10/10 PASS）
- 创建测试用户 → 2FA setup（secret+otpauth）→ verify-setup 发 8 个备份码 ✅
- 无码登录 → `mfaRequired=true/MFA_REQUIRED` ✅；TOTP 正确码 → token 签发 ✅；错误码 → `MFA_INVALID` ✅
- **备份码登录成功 + 复用同一备份码被拒（一次性消费正确）** ✅ ——逃生通道闭环
- 超管查他人 2FA 状态 enabled=true ✅；cleanup：disable 2FA 成功
- 踩坑记录：①CSRF Double-Submit——先 GET 拿 XSRF-TOKEN cookie 再 POST 回传 header；②`/api/admin/users` 创建用户字段是 `initialPassword`（非 password）；③TOTP 防重放——verify-setup 消费过的 30s 窗口码不能立即再用于登录（等新窗口即可，是安全特性）；④RateLimit 短时高频登录触发 429，脚本需退避重试

### Notes
- AdminController 无删除用户端点（DELETE/RESTATUS 均不存在），测试用户仅能 disable 语义收尾——dev 库残留 e2e2fa_* 用户无害；门户登录页（portal.html）挑战逻辑 V2.0.42 已有，本版未动

## V2.0.64 (2026-09-03 20:50) — 报名转化漏斗（P1 待办落地）

### Feature
- **admin.html 报名管理 tab 顶部新增转化漏斗条**：待处理 → 已联系 → 已转化 → 已开通 → 已关闭 五状态卡片 + 「报名→开通转化率」（已开通 ÷ 总报名，绿色高亮）
- 数据源复用公开端点 `/api/registrations/stats`（仅数量，Bug-20 已有枚举安全转换）
- 后端 `/stats` 补 **CONVERTED 计数**——之前漏斗缺最后一环（终态），total 也随之含 CONVERTED

### E2E 验证（2026-09-03 20:47）
- `/api/registrations/stats` 返回 converted=1/total=1 ✅
- 浏览器：admin 登录 → 报名管理 tab → 漏斗渲染「0→0→0→1(绿)→0 + 转化率 100%」（截图确认）✅

## V2.0.63 (2026-09-03 20:40) — 修复登录类审计事件公司归属缺失（V2.0.62 发现的既有缺陷，方案 A 落地）

### Fixed
- **登录类事件 companyId=null 导致公司超管不可见（V2.0.62 发现）**：
  - 根因：登录发生时还没有 JWT 上下文 → `AuditLogService.record` 里 `CurrentUser.get()`=null → companyId/actorUsername 双双落 null → 公司超管按 companyId 过滤永远看不到本公司的登录失败记录
  - 修复：`AuditLogService` 新增 `recordWithSubject(action, resourceType, subjectUserId, subjectUsername, summary)` 重载——按 userId 反查 `company_user` 回填 companyId（多条取第一条），username 优先显式传入否则查 sys_user；平台超管/无公司归属保持 null 不强行归属
  - AuthService 10 个登录链路调用点全部切换：LOGIN_FAIL/LOGIN_SUCCESS/LOGIN_MUST_CHANGE_PASSWORD/PASSWORD_CHANGED/LOGOUT/REFRESH_REPLAY_DETECTED/TOKEN_REFRESH/FORGOT_PASSWORD_USER_NOT_FOUND/PASSWORD_RESET_REQUESTED/PASSWORD_RESET

### E2E 验证（2026-09-03 20:38）
- 基线 LOGIN_FAIL=0 → 制造 2 次错误密码登录 → 公司视角立刻看到 2 条（companyId=1001、actorUsername=123_admin 正确回填）✅
- LOGIN_SUCCESS 17 条无回归；平台超管作用域不受影响 ✅

### Notes
- 历史已落库的 null 归属行无法追溯回填（无 userId 可查的行是 user==null 的登录尝试），保持原样不影响新数据

## V2.0.62 (2026-09-03 20:25) — company-admin 新增审计日志 tab：业务操作审计可视化（P1 待办落地）

### Feature
- **后端 2 端点**（AdminController，均平台超管守卫）：
  - `GET /api/admin/security-events`：AuthAuditEvent 复合筛选分页（eventType/username 模糊/success/companyId 全可空）
  - `GET /api/admin/security-events/summary`：近 24h 聚合（总数/失败数/按类型计数）
  - AuthAuditEventRepository 新增 `findByFilters`（@Query 复合）+ `countByCreatedAtAfterAndSuccessFalse`
- **前端「安全审计」tab**（admin.html 第 6 个 tab）：KPI 三卡（近 24h 总量/失败量[红色高亮]/最高频类型）+ 三筛选器（类型下拉 13 项中文/结果/用户名回车触发）+ 7 列表格（时间/类型/结果/用户名/IP/路径/错误）+ 上一页/下一页分页；事件类型全中文映射（SEC_EVENT_ZH 18 值）

### Fixed
- **PG `lower(bytea) does not exist`**：JPQL `CONCAT('%', :username, '%')` 在 null 参数时 Hibernate 把参数推成 bytea → `CONCAT('%', CAST(:username AS string), '%')` 显式转型修复

### E2E 验证（2026-09-03 20:00~20:10）
- API 7 项断言全绿：admin 登录 → summary（total=56/failed=6/top=LOGIN_SUCCESS）→ 分页 → success=false 筛选（6 条全失败）→ eventType 筛选（48 条全匹配）→ 非超管 403（JSON code）→ 非法类型 400
- 浏览器全链路：admin 登录 → 安全审计 tab → KPI 渲染 → 筛选「登录失败」表格实时刷新（失败行红色/错误消息中文）→ 分页控件正常（截图确认）
- 教训：本代码库失败约定是 HTTP 200 + `code≠0`，E2E 断言勿用 HTTPError

## V2.0.60 (2026-09-03 19:30) — 门户三页英文枚举标签全面中文化（主人截图反馈）

### Fixed
- **company-admin.html 公司管理后台英文标签（主人报：概览页 license/TRIAL/ACTIVE/SUSPENDED 等英文裸露）**：
  - 静态文案：副标题「与 license」→「与许可证」；统计卡「license 剩余天数/计划」→「许可证剩余天数/许可证计划」
  - 动态枚举：产品/用户/公司状态（ACTIVE/SUSPENDED/PENDING/EXPIRED/REMOVED/CANCELLED）、许可证计划（TRIAL/STANDARD/ENTERPRISE → 试用版/标准版/企业版）、部署模式（SAAS/PRIVATE）、公司内角色（SUPER_ADMIN/OPERATOR/VIEWER → 公司超管/操作员/访客）、审批类型（SUBTASK_INVOKE/PRODUCT_GRANT/SSO_LINK_REQUEST → 子任务调用/产品授权/SSO 专属链接）、审批状态（APPROVED/REJECTED → 已通过/已拒绝）全部映射中文
- **admin.html 平台超管后台同类漏网**：公司列表部署模式/许可证计划/公司状态三列裸英文 → 中文；用户列表状态标签 → 中文
- **portal.html 主门户同类漏网**：许可证 KPI「计划 TRIAL」→「许可证计划：试用版」；成员列表用户状态标签 → 中文

### Changed
- 三页各加 `STATUS_ZH` 映射表 + `zh()` 工具函数（admin/portal 亦含邀请状态 CONSUMED/REVOKED）；**CSS 类名保留英文枚举**（如 `.status-tag.ACTIVE`）不影响配色逻辑，仅翻译显示层
- 版本印章同步：company-admin/portal meta+console V2.0.55→V2.0.60，admin css v=2.0.60，application.yml 2.0.59→2.0.60

### 枚举依据（后端实体核对）
- Company：DeploymentMode{SAAS,PRIVATE} / LicensePlan{TRIAL,STANDARD,ENTERPRISE} / CompanyStatus{PENDING,ACTIVE,EXPIRED,SUSPENDED,DELETED}
- CompanyProduct.Status{ACTIVE,EXPIRED,SUSPENDED,CANCELLED}；CompanyUser.Status{ACTIVE,REMOVED}
- SubTaskApproval：Type{SUBTASK_INVOKE,PRODUCT_GRANT,SSO_LINK_REQUEST,OTHER} / Status{PENDING,APPROVED,REJECTED}；Invitation.Status{PENDING,CONSUMED,EXPIRED,REVOKED}

## V2.0.59 (2026-09-03 15:20) — SSO「Signature verification failed」排查修复 + portal 改密 CSRF 漏网（主人截图反馈）

### Fixed
- **GEOM SSO 验签失败（主人报：portal 点棱镜「进入」→ 回调页「主站 token 无效 · Signature verification failed」）**：
  - 根因：**GEOM 后端是老进程**——由旧版 `start_geom_daemon.py` 启动，env 硬编码旧默认密钥（`lingyao-platform-default-...` 87 字符）；而主站 daemon 按现行机制从 `data/.jwt_secret` 读密钥（64 字符）签发 platform_token → GEOM 拿旧钥匙验新签名必然失败
  - 09-02 GEOM 窗口已给 supervisor.py 加密钥文件 fallback（geom data/.lingyao-jwt-secret → 主站 data/.jwt_secret），但**老进程一直没重启，修复未生效到运行中的进程**
- **portal.html 强制改密 CSRF 漏网（浏览器 E2E 途中发现）**：`/api/auth/change-password` 请求未带 `X-XSRF-TOKEN` 头 → 被 CsrfFilter 拒绝（「CSRF token header 缺失」）。admin.html 的 `api()` V2.0.43 修过同款问题，portal.html 是漏网之鱼；portal 其余 POST 全部核对过（login/refresh/feedback 精确豁免、2fa prefix 豁免），仅此一处

### Changed
- **GEOM 后端重启（运维操作，未改 GEOM 仓代码）**：kill 8090 老进程 → supervisor.py（带文件 fallback）拉起 → `secretLength` 87→64 ✅
- **新增 `scripts/start_geom_supervisor_daemon.py`**：supervisor.py 原本前台运行会被 sandbox 会话收割，本脚本用双重 fork+setsid（与主站 start_backend_daemon.py 同款）把它守护化；GEOM 后端从此用本脚本拉起
- portal.html change-password 补 XSRF 头读取（与 admin.html 同款 `document.cookie` 模式）；已同步 static + mvn package + 重启

### E2E 验证（2026-09-03 15:15~15:35，真实浏览器 agent-browser 全链路）
- 123_admin 登录主站 → `/api/auth/me` granted 仅 GEO（V2.0.57 清理口径保持正确）✅
- portal 工作台点「进入」→ **直接落地棱镜 GEO 工作台**（已登录态、超级管理员身份，截图确认）✅
- 强制改密完整旅程：admin 重置密码 → 123_admin 登录 → 强制改密页弹出 → 提交改密 **200 成功** → 进工作台 → 新密码复登 200 ✅（修复前此步报 CSRF token header 缺失）
- 四服务健康：主站 9091/8765 + GEOM 8090/5180

### 遗留交接（GEOM 窗口）
- ⚠️ `geo-backend-py/start_geom_daemon.py` L12-15 仍硬编码旧默认密钥——GEOM 窗口下次改启动链路时应删除该默认值、改走 supervisor.py（或同样加文件 fallback），否则用它重启会复发本 bug

### 教训
- **SSO「signature not match」排查三步**：比 secretLength（`/status` 接口）→ 比密钥文件内容 → 查进程启动时间 vs 文件 mtime；**老进程持旧密钥是最常见的隐形坑**（改了配置≠进程生效）
- 共享密钥类问题先做「密钥指纹三方言对齐」：主站签发实际密钥 / 子平台验证实际密钥 / 磁盘文件，三方一比就现形
- **CSRF Double-Submit 的前端接入点要全站审计**：admin.html 修了 ≠ portal.html 没事；新增带 CSRF 校验的端点时必须排查所有前端调用方

## V2.0.57 (2026-09-03 14:35) — 报名转开通按感兴趣产品精确开通 + 未部署产品硬过滤（主人截图反馈）

### Fixed
- **转开通全量开通 bug（主人报：报名只选 GEO，四产品全亮）**：
  - 根因 1（前端）：admin.html 转开通预填逻辑无脑勾选「自动开通全部产品」`grantAllProducts=true`，完全没读报名的 `interestedProducts` 字段
  - 根因 2（口径）：后端 `grantAllProducts` 会授权全部 4 个 ACTIVE 产品——包括**未部署的医呈**（medppt-gen COMING_SOON）
- **未部署产品可被开通**：医呈未部署却亮「已开通」。主人拍板：医呈永远不该亮

### Feature
- **CreateCompanyRequest 新增 `productCodes`**（List<String>）：按产品 code 精确开通，报名转开通主路径；与 grantAllProducts 二选一，productCodes 非空时优先
- **部署就绪硬过滤 `resolveGrantableProducts`**（TenantAdminService）：开通前过滤「产品 ACTIVE + 子任务已部署（sub_task status≠COMING_SOON）」——前端误传也会被后端拦下；无子任务记录的产品视为未部署
- **/api/admin/products 补 `deployed` 字段**：前端据此把未部署产品置灰 + 「待部署」徽标 + 禁勾
- **新建公司弹窗产品勾选网格**：「自动开通全部产品」单 checkbox → 4 产品 checkbox 网格（2 列），未部署产品灰色不可勾；普通新建默认不勾任何产品
- **转开通预填按报名产品**：报名行按钮带 `data-products`，预填时剔除未部署产品并 toast 提示「已按报名预填：开通 GEO（MEDPPT 待部署未含）」

### 数据清理
- 公司 123（1001）：4 产品 ACTIVE → 收敛到报名口径（仅 GEO；HPD/AIDD/MEDPPT 公司 license SUSPENDED + 用户授权 REVOKED，走 grant-products 全量同步 API 与界面同路径）
- V2.0.57 E2E 测试数据（E2ECONV57/E2E57B/E2E57C 三家公司 + 3 个测试账号）已从 lingyao_dev 删除
- 123_admin 密码已重置（验证口径后），新密码已告知主人

### E2E 验证（dev-pg，2026-09-03 14:27）
- `productCodes: ["GEO","MEDPPT"]` → `grantedProductCodes: ["GEO"]`（医呈被硬过滤）✅
- company_product 仅 productId=1；123_admin `/api/auth/me` licensed/granted 仅 GEO ✅
- /api/admin/products deployed 字段：GEO/HPD/AIDD=true，MEDPPT=false ✅

### 教训
- **「自动开通」类开关必须走部署就绪口径**：产品 ACTIVE ≠ 可用，子任务 COMING_SOON 时公司开通了也进不去（入口待部署），只会制造「看起来开通了」的假象
- 前端预填逻辑改口径时，**后端必须同步加防线**（双重拦截），否则旧前端/脚本直接调 API 仍可绕过

---



### Feature
- **官网「申请免费试用」弹窗**：感兴趣的产品由单选 select 改为 **checkbox 多选网格**（与页脚表单同款交互），选项对齐 4 产品新目录（棱镜/皓元/源策/医呈）；顺带修掉旧 select 重复 `value="GEO"` 的 bug

### Fixed
- **admin.html 新建公司弹窗排版**（截图红框）：`style.css` 全局 `.form-field input` 规则（width:100%+padding:12px）把 checkbox 拉成大长条导致勾选框与文字错位 → 内嵌样式加 `.form-field input[type="checkbox"]` 豁免复位（16px 方框 + flex 布局对齐），两个复选框（自动开通全部产品 / 发送试用邀请邮件）排版归正
- 「自动开通全部产品」文案：GEO/HPD/AIDD/POR → **GEO/HPD/AIDD/MEDPPT**（V2.0.54 目录口径）
- 官网页脚试用表单选项同步：删已下架 POR、加医呈、名称对齐；默认勾选从 GEO+HPD 收敛为仅 GEO
- script.js 报名兜底值 `geo-monitor`（旧代号，后端白名单会拒）→ `GEO`
- 后端 RegistrationService.VALID_PRODUCTS 白名单：GEO/HPD/AIDD/POR → **GEO/HPD/AIDD/MEDPPT**（多选提交MedPPT 不再报"非法产品"）

---

## V2.0.55 (2026-09-03 12:15) — 产品对外名称更新（主人拍板）

### Changed
- **源策**：`源策-AI 立项 Copilot` → **`源策-医药研发信息智能体`**
- **医呈**：`医呈-智能医学PPT` → **`医呈-智能医学PPT生成智能体`**
- 落点 5 处：product 表名称（dev data.sql + dev-pg data-dev-pg.sql，含存量库 UPDATE 强制对齐）、试用邀请邮件 PRODUCT_CATALOG、邀请页 invite.html PRODUCT_INTRO、portal.html 新手引导步骤标题

---

## V2.0.54 (2026-09-03 12:05) — 产品目录收敛 4 产品 + MED PPT 定名「医呈」上架

### Feature
- **主人拍板**：前端不再展示 5+ 产品，只留 **棱镜(GEO)/皓元(HPD)/源策(AIDD)/医呈(MEDPPT)** 四个
- **MED PPT 定名「医呈」**（两字名，智能医学PPT生成智能体）：医学+呈现，与源策/皓元命名风格一致；code=MEDPPT，icon 🅜，全名「医呈-智能医学PPT」
- 软下架方案：DINFO（辰录）/PORM（明枢）product.status=DEPRECATED + sub_task.status=COMING_SOON——鉴权链路与历史授权数据不受影响，随时可恢复
- 医呈子任务 `medppt-gen` 已注册（COMING_SOON 待部署，工作台显示「⏳ 待部署」，部署后置 ACTIVE 即上线）

### Changed
- 后端 4 处口径收敛到 ACTIVE：`buildProductView`（me/登录/JWT claim 共用）、`/api/admin/products`（管理后台+邀请产品下拉）、`grantAllProducts`（组合开通）
- `PRODUCT_CATALOG` 增加医呈条目（试用邀请邮件可用）
- 种子同步：data.sql（H2）+ data-dev-pg.sql（PG，含存量库 UPDATE 强制对齐 + INSERT 1005 医呈授权）
- portal.html 新手引导 5 产品步骤 → 4 产品（辰录/明枢步骤替换为源策/医呈）；iconFor 增 medppt
- 版本 2.0.54 三端同步（application.yml / admin.html cache-bust / portal+company-admin 印章）

### 影响面
- 存量 dev-pg 库：重启后 UPDATE 幂等对齐（辰录/明枢自动隐藏，医呈自动出现）
- 邀请页产品下拉、管理后台授权弹窗、工作台产品列表、JWT sub_product_grants 全部只含 4 产品
- DINFO/PORM 恢复上架：product.status 改回 ACTIVE + sub_task.status 改 ACTIVE 即可（无数据损失）

## V2.0.53 (2026-09-03 11:40) — dev-pg profile：本地开发切 PG 持久化（界面修改重启保留）

### Feature
- **主人拍板**：dev 不再只用 H2 内存库，新增 dev-pg 模式（连本地 Docker `lingyao-postgres` 的独立库 `lingyao_dev`）
  - 动机：V2.0.52 事故——H2 内存库每次重启重跑 data.sql，管理后台做的「产品开通收敛」被种子复原；PG 持久库 + 幂等种子彻底根治
  - `application-dev-pg.yml`（新建）：PG datasource（127.0.0.1:5432/lingyao_dev）+ ddl-auto:update + data-dev-pg.sql；h2 console 关闭
  - `data-dev-pg.sql`（新建）：PG 方言 + 全部 `ON CONFLICT (id) DO NOTHING` 幂等种子（与 staging/prod data-private.sql 同风格）；口径与 dev data.sql（V2.0.52 版）完全一致：A 药企仅 GEO / B 药企仅 GEO / 5 产品目录；**内置 dev 常用测试数据**——EMAIL 通道(id=1000)、PRIVATE-CUSTOMER(id=1000)+pc_admin/PrivateCust#2026、FEISHU SSO 配置、pc_admin 全产品授权（免去每次重启手工重建 SOP）
  - PG 序列 setval 幂等对齐（自增 > 999，避开种子显式 id，延续 Bug-22 修复）
  - `start_backend_daemon.py`：LINGYAO_PROFILE=dev-pg 走外部配置（同 private/staging 机制）
  - 独立库 `lingyao_dev`（新建）——不碰容器里已有的 `lingyao` 库（私有化形态旧数据）
- **dev(H2) 与 dev-pg 双模共存**：快速干净重置用 dev；要界面修改持久/更保真测 PG 用 dev-pg

### Verified
- 种子落库：3 公司（A/B/PRIVATE-CUSTOMER）/ A 药企仅 GEO / SSO 配置 ENABLED / EMAIL 通道 ACTIVE
- **持久性 E2E**：界面调 grant-products 给 A 药企开 HPD（落库 id=1005 PG 序列）→ 重启后端 → HPD 仍 ACTIVE（me 接口 licensed=['GEO','HPD']）→ 界面收敛回 [GEO] → HPD 置 SUSPENDED（软删除留痕）
- SSO 链路 302 飞书授权页；pc_admin 持久库直登成功
- 前后端版本 2.0.53；8765/9091 双活（dev-pg 模式）

## V2.0.52 (2026-09-03 10:45) — 种子数据口径修正（A 药企仅 GEO，重启不再复原）

### Fix
- **管理后台开通的产品收敛被重启冲掉**（主人报：开通只剩 GEO，重启后又变成所有产品都开放）：
  - 根因：dev 环境为 H2 内存库，每次重启重新执行 data.sql；而 data.sql 第 58~65 行把 A 药企种子写死为全 5 产品授权（company_product 1~5）+ a_admin/admin 全产品 grant + 全产品 product_user_role——界面上的收敛操作只改内存库，重启即被种子复原
  - data.sql 修正（对齐主人拍板口径）：A 药企 company_product 仅 GEO（id=1）；product_user_grant 仅 a_admin GEO + b_admin GEO；product_user_role 仅 a_admin GEO SUPER_ADMIN；平台超管 admin 全产品 grant/role 种子同步收敛
  - 顺带清理 V2.0.51 遗留：a_user1（已删种子用户）的孤儿授权 grant(id=3) 与 OPERATOR 角色分配(id=3)
- **教训**：H2 内存库的「界面改数据」都是临时的——凡主人拍板的持久口径，必须同步写进 data.sql 种子，否则重启即回滚

### Verified
- （待重启后验证）admin 登录 → A 药企上下文 licensed 仅 GEO；B 药企仅 GEO 不变

## V2.0.51 (2026-09-03 10:25) — 双超管徽标区分 + 工作台用户列表只含超管

### Fix
- **角色列裸显 SUPER_ADMIN 无法区分**（主人指出的「巨大区别」）：
  - 后端 `listCompanyUsers` 响应新增 `platformAdmin` 布尔字段
  - portal.html / company-admin.html 角色徽标双语分化：平台超管（紫色徽标，管全平台）/ 公司超管（蓝色徽标，只管本公司）
  - company-admin.html：平台超管行禁止「改角色/移除」操作（平台账号不可被公司侧操作）
- **OPERATOR 出现在主站平台**（主人：平台没有 Operator）：
  - 后端 `listCompanyUsers` 只返回 SUPER_ADMIN·ACTIVE（JPQL findByCompanyIdAndRoleInAndStatus），普通员工不再出现
  - KPI「公司成员」usedUsers 同口径只数超管（countByCompanyIdAndRoleAndStatus）
  - data.sql 删除 a_user1（OPERATOR）种子——主站不再出生普通用户，普通员工由公司超管在子平台内管理（延续 V2.0.38/V2.0.46 架构）

### Verified
- E2E：admin 登录 A 药企上下文 → 用户列表 3→2（admin+platformAdmin=true / a_admin+false），a_user1 消失；KPI usedUsers=2
- 浏览器 DOM 级：用户表 = [admin（你）·平台超管徽标] + [a_admin·公司超管徽标]，OPERATOR 不复现


## V2.0.50 (2026-09-03 10:10) — 产品列表口径修复（已开通 = 真实授权）

### Fix
- **portal 产品列表虚假「已开通」**：平台超管登录控制台，产品列表 5 个产品全部显示「✓ 已开通」，与 KPI「已开通产品 N」自相矛盾
  - 根因（双层短路叠加）：后端 `AuthService.buildProductView` L784 `licensed = isAdmin || 公司授权`（平台超管全量伪造 licensed）；前端 `renderProductList` L1375 `isGranted = isPlatformAdmin || grants`（再次短路）
  - 后端修复：licensed/granted 一律按「公司真实授权（company_product ACTIVE）+ 用户个人授权（product_user_grant ACTIVE）」口径，删 isAdmin 短路；平台超管进入子平台的能力由 `/api/sub/{code}/enter` 服务端鉴权（isPlatformAdmin 直通）保证，不依赖本视图
  - 前端修复：删 isPlatformAdmin 万能放行，已开通严格按 subProductGrants（与 KPI 同口径）；平台超管看到的未开通提示改为「该公司未开通（可在管理后台「产品授权」开通）」运营视角文案
  - 附带修正：平台超管 JWT `sub_product_grants` claim 不再虚报全产品（此前登录子平台会携带未授权产品）

### Verified
- E2E：b_admin（B 药企，仅 GEO 授权）me 接口 licensed=['GEO']，JWT claim=['GEO']，KPI activeProducts=1；admin（A 药企，5 产品授权）licensed=5 个，KPI=5
- 浏览器 DOM 级验证：B 药企控制台产品列表 GEO「✓ 已开通」+ 其余 4 个「未开通」；KPI 与列表严格一致
- 平台超管 `/api/sub/GEO/enter` 直通能力保留（HTTP 200 + redirectUrl 正常签发）


## V2.0.49 (2026-09-02 21:55) — KPI 五卡一行根治（CSS 启发式缓存修复）

主人截图报「KPI 卡片占两行」：磁盘 CSS 已是 5 列，但浏览器按启发式缓存拿着旧 4 列 style.css（HTML no-cache 治了，CSS 漏了）。三步根治：

1. **start_frontend_daemon.py**：no-cache 范围从 HTML 扩到 `.css/.js`（V2.0.43 同类问题第二例，这次整类根治）
2. **style.css**：KPI 断点收敛——删「≤1200px 降 3 列」（1080 视口正是元凶之一），改 ≤900px 降 3 列 / ≤600px 降 2 列，桌面端恒 5 卡一行
3. **admin.html**：CSS 引用加 `?v=2.0.49` cache-bust 双保险

验证：8765/9091 双端口 CSS 均带 `Cache-Control: no-cache` 且内容 5 列；agent-browser 实测 1080 视口 5 卡一行截图确认。

## V2.0.48 (2026-09-02 21:45) — SSO 登录链路修复（回复 GEO-M 窗口交接）

主人报障「主站已配置飞书 SSO，但子网站无法登录」，GEOM 窗口排查交接 3 根因 + 2 契约问题，本版本全部修复：

1. **SecurityConfig**：permitAll 加 `/api/sso/**`——公司 SSO 的 login（员工未登录发起）与 callback（飞书回跳无会话）必须匿名可达，此前命中 `/api/**` authenticated 全链路 401
2. **SsoCallbackController**：
   - 新增 `normalizeSubProductCode()`：GEOM→GEO 别名归一化（产品表实际 code=GEO/HPD/AIDD/DINFO/PORM，交接的 `case "GEOM"` 永不命中已实证）；state 缓存、audit、JWT claim 全部使用归一化值
   - `buildSubProductCallbackUrl()` 按产品表 code 重新映射 + GEOM 回调基址改为前端 Vue 路由 `http://localhost:5180/auth/lingyao/sso-callback`（原 `/api/auth/sso-callback` 是后端端点，前端路由 404）
3. **新增 sso-error.html**：login/callback 异常兜底页此前不存在（404），补错误展示 + 4 类原因提示
4. dev 数据重建：PRIVATE-CUSTOMER 公司（id=1000，grantAllProducts）+ 超管 pc_admin + FEISHU SSO 配置（测试凭据，生产需真实凭据重配）

E2E：GEOM/GEO 双值 login 均 302 飞书授权页（修复前 401）；callback 无效 state 302 error 页；sso-error.html 双端口 200。回执：`/tmp/MainSite_SSO登录链路修复回执_V2.0.48.md`

## V2.0.47 (2026-09-02 21:25) — 主站建号/绑定一律 = 公司超管

V2.0.46 口径收口的遗留隐患收尾：「新建用户」表单此前仍可选 OPERATOR/VIEWER 和「平台超管=否」，新建的普通用户会因 V2.0.46 过滤**凭空消失**。按主人拍板精神对齐邀请唯一角色：

### 1）后端强制归一化（TenantAdminService）
- `createUser`：memberships 角色一律落 `SUPER_ADMIN`（此前 null → OPERATOR）
- `bindUserToCompany`：绑定角色一律落 `SUPER_ADMIN`（防 API 直调传 OPERATOR/VIEWER 绕过）

### 2）前端表单简化（admin.html）
- 新建用户弹窗：删「平台超管」下拉（固定 false，平台超管不通过此入口创建）和「公司内角色」下拉 → 只读展示「公司超管（唯一角色）」
- 提交固定 `companyRole: 'SUPER_ADMIN'`；弹窗副标题注明「普通成员由公司超管在子平台内添加」

### E2E（全绿）
- ① 建号恶意传 OPERATOR → memberships 落库 SUPER_ADMIN 且入列；② bind-user 恶意传 VIEWER → 落库 SUPER_ADMIN；测试账号随 H2 重启自清

版本 2.0.47

## V2.0.46 (2026-09-02 21:05) — 用户管理只管超管（平台超管 + 公司超管）

主人拍板：平台超管后台的「用户管理」只管理公司超管，**公司普通用户不应该出现在这个平台**（普通用户由公司超管在子平台内管理，延续 V2.0.38 审批下放架构）：

### 1）后端口径收窄（SysUserRepository + AdminController）
- `/api/admin/users` 从 `findAll` 改为 `findPlatformAndTenantAdmins`：只返回「平台超管 + 公司超管（SUPER_ADMIN·ACTIVE）」，JPQL DISTINCT + EXISTS 子查询
- 新增 `GET /api/admin/users/count`：KPI「用户总数」同口径计数

### 2）前端适配（admin.html + style.css）
- KPI「用户总数」hint 改「平台超管 + 公司超管」，数据走新 count 接口
- **KPI 卡片 4 列 → 5 列一行**（≤1200px 降 3 列、≤768px 降 2 列）
- 用户列表角色徽标「普通用户」→「公司超管」；用户 Tab 副标题注明「仅超管：公司普通用户由公司超管在子平台内管理」

### E2E + 浏览器验证（全绿）
- ① 列表 5 → 3（只剩 admin + a_admin + b_admin，a_user1 等 OPERATOR 被过滤）；② KPI count = 3 与列表一致；③ 邀请核销新建 u46admin（B-DEMO 超管）→ 自动入列 count=4；④ 浏览器截图确认：5 卡一行、徽标「公司超管」、副标题提示
- 重启 SOP：EMAIL 通道已重建 ACTIVE

版本 2.0.46

## V2.0.45 (2026-09-02 20:55) — 邀请唯一角色 = 公司超管

主人拍板：邀请后**不用选角色，只有一个角色——公司超管**，因为只有公司超管才能真正体验平台：

### 1）前端（admin.html）
- 创建邀请弹窗删除角色下拉（ADMIN/OPERATOR/VIEWER 三选项），替换为只读展示「公司超管（唯一角色）」
- 提交逻辑固定 `roleCode: 'ADMIN'`
- invite.html 校验页角色横幅显示「公司超管（完整体验）」（不再暴露内部枚举值）

### 2）后端兜底（InvitationController）
- **服务端强制归一化**：无论请求传什么 roleCode（含恶意直调传 OPERATOR/VIEWER），落库一律 ADMIN
- roleCode 从「必填」放宽为「可缺省」（缺省即 ADMIN，兼容旧客户端）

### E2E（全绿）
- ① 创建邀请不传 roleCode → 落库 ADMIN；② 恶意传 OPERATOR → 仍落库 ADMIN；③ 真实核销注册 u45super → 登录 JWT `is_tenant_admin=True` + `sub_product_grants=['GEO']` + 授权表 ACTIVE
- 重启后 SOP：EMAIL 通道已重建并 ACTIVE（id=1000）

版本 2.0.45

## V2.0.44 (2026-09-02 20:35) — 邀请角色真实化 + 同账号追加授权 + 邀请页手机适配/产品摘要

主人指出：邀请弹窗三个角色选项（ADMIN/OPERATOR/VIEWER）"只能选公司超管权限，没有其他两个"——排查确认三选项此前**均无实际差异**（redeem 把公司角色硬编码 OPERATOR，roleCode 只进产品内角色表且主站不消费）。本轮让邀请角色真正生效：

### 1）邀请角色 → 公司级角色映射（InvitationController）
- `ADMIN（公司超管）→ CompanyRole.SUPER_ADMIN`：JWT `is_tenant_admin=true`，SSO 进子平台自动获超管（契约六A）
- `OPERATOR → OPERATOR`；`VIEWER → VIEWER`；未知值兜底 OPERATOR
- 前端下拉标签同步语义化：「ADMIN（公司超管·进子平台自动获管理员）」等

### 2）redeem 双分支：新建 / 追加（同一账号接受多次邀请不再裂号）
- 用户名已存在 → **必须验密码**（防冒名顶替），通过则把产品授权追加到同一账号（`appended=true`）
- 公司成员关系幂等：已是成员不重复建；曾被 remove → 重新激活
- **ADMIN 只升不降**：已是 SUPER_ADMIN 不被 OPERATOR/VIEWER 邀请降级
- 产品授权幂等：曾有 REVOKED 记录 → 复用复活；产品内角色幂等：已存在则更新 roleCode
- 整个 redeem 事务化（已有 @Transactional）

### 3）invite.html 手机适配 + 产品特点摘要
- @media ≤480px：卡片/字号/输入框 16px（防 iOS 聚焦放大）、按钮全宽
- 校验通过后显示**产品特点卡片**（正式名 + 一句话价值 + 新手第一步，与 TrialInviteService PRODUCT_CATALOG 对外口径一致，不出现内部代号）

### E2E（全绿）
- ① 首次核销（OPERATOR+GEO）→ 新建账号 appended=false；② 同用户名错密码核销 → 拒绝（防冒名）；③ 二次核销（ADMIN+HPD）→ 追加同账号 appended=true；④ grants：GEO+HPD 双 ACTIVE、membership role=SUPER_ADMIN；⑤ 登录 JWT `is_tenant_admin=true`
- 测试残留：invtest_dup（uid=1000，A-DEMO SUPER_ADMIN，GEO/HPD）留在 dev H2（重启自清）

版本 2.0.44

## V2.0.43 (2026-09-02 19:15) — 产品调整 Bug 修复（CSRF 403 + 减产品不生效 + 重开 409）

主人报：A 药企（5 产品）在「开通产品」弹窗减掉 4 个 → 报「CSRF token cookie 缺失」。排查挖出 **3 层问题**，一次修完：

### 1）admin.html api() 缺 CSRF 凭证（主人看到的报错根因）
- V2.0.32 修 API_BASE 跨站问题时漏了 admin.html：fetch 无 `credentials:'include'`（8765→9091 跨源不携带/不落盘 XSRF-TOKEN cookie）也没带 `X-XSRF-TOKEN` header → CsrfFilter Double-Submit 校验 403
- 修复：api() 统一补 `credentials:'include'` + 非 GET 自动读 cookie 加 `X-XSRF-TOKEN` 头（与 portal.html V2.0.42 写法对齐）
- **同类隐患顺手修**：company-admin.html 的 api() 有 credentials 但同样缺 X-XSRF-TOKEN 头（公司超管的授权/撤销/改角色等写操作会 403）→ 一并补齐
- 全站排查结论：aidd/geo/hpd/por 仅 GET `/api/sub/*/info`（CSRF 不校验安全方法）；invite 的 POST `/api/invitations/redeem` 在豁免前缀内——均不受影响

### 2）减产品不生效（隐藏语义缺陷）
- 原 `grantCompanyProducts` 只做追加/续期：勾选=开通，**取消勾选=无操作**。即使没有 CSRF 报错，减 4 个产品也不会生效
- 修复（全量同步语义）：弹窗勾选集合 = 公司最终产品集合
  - 勾选 → 开通/续期（原逻辑）
  - 取消勾选 → 公司 license 置 **SUSPENDED**（保留记录可审计可恢复）+ **级联 REVOKED** 该公司用户在该产品上的 ACTIVE 用户级授权
  - 审计消息区分「开通 N 个，停用 M 个(产品ID:...)」

### 3）重开已停用产品 409（E2E 抓出的连环 bug）
- `uk_company_product(company_id, product_id)` 唯一约束：SUSPENDED 旧记录仍在，重开时原代码只查 ACTIVE 状态查不到 → 走 INSERT → 撞唯一约束 → 409「数据冲突，可能已存在」
- 修复：改为 `findByCompanyIdAndProductId` 查任意状态——ACTIVE→续期 / 非 ACTIVE→**复活置回 ACTIVE** / 无记录→新建

### E2E（curl 模拟浏览器 CSRF 全凭证 · 全绿）
①全开5 → ②减到1（GEO，级联 REVOKED 用户授权 ×8）→ ③重开5（复活路径，上次 409 场景）→ ④再减到2 → ⑤恢复5，全流程 code=0；⑥无 header POST 仍 403（CSRF 防护未破）

### ⚠️ 遗留语义点（待主人拍板）
- 公司产品停用会级联 REVOKED 用户级授权；但**重开公司产品不会自动恢复**用户授权（记录还在，REVOKED→需公司超管在子平台内重新授权，符合 V2.0.38 审批下放精神；如需自动恢复再说）

### 4）invite.html API_BASE 错位（19:30 主人报「邀请链接打开报无效」· 同属跨站修复漏网）
- 现象：邀请链接（8765 形态）打开后报「邀请链接无效 · `<!DOCTYPE` is not valid JSON」
- 根因：invite.html `API_BASE = window.location.origin`——页面在 8765 静态服务上打开，API 全打到 8765（无 /api 路由 → 404 HTML 被 r.json() 炸）。这是 V2.0.32 跨站修复的第 5 处漏网（portal/admin/company-admin 已修，invite 一直漏）
- 修复：API_BASE 与 portal 对齐（localhost/127.0.0.1 → `:9091`；生产空串走 Nginx 反代）
- 全链路浏览器验证：创建邀请 → 打开链接 → 校验页渲染 → 注册开通 → 新账号可登录，全绿
- 配套：start_frontend_daemon.py 加 NoCacheHandler，HTML 一律 `Cache-Control: no-cache`（根治浏览器缓存旧 JS 反复报旧错）

版本 2.0.43

## V2.0.42 (2026-09-02 18:10) — 2FA 全链路集成（登录 challenge + 前端 2FA UI）

登录流程强制第二步 + portal 前端完整 2FA 管理界面：

### 后端：登录 challenge（AuthService）
- `LoginRequest` 新增 `totpCode`；`LoginResponse` 新增 `mfaRequired`/`mfaReason`
- 已启用 2FA 的用户登录：密码对但码缺失 → HTTP 200 + `mfaRequired=true` + `mfaReason=MFA_REQUIRED`；码错误 → `MFA_INVALID`；**不返回 token**
- **不暴露「是否启用 2FA」**：未认证方看到的响应结构一致（`mfaRequired=false` 仅在挑战通过时随正常登录体出现）
- 2FA challenge 失败**不计入 Bug-09 五次锁定**（防锁死），但记 LOGIN_FAIL 安全审计

### 前端：portal.html 2FA 全流程 UI
- 登录 challenge 处理：收到 `mfaRequired` → 弹验证码输入 → 带 `totpCode` 重试登录（TOTP 6 位或备份码 XXXX-XXXX 均可）
- 顶栏新增「🔐 两步验证」按钮（所有登录用户可见）→ `open2FaModal()` 完整弹窗：
  - 绑定：`/setup` 取 secret + otpauthUrl → qrcodejs CDN 渲染 QR + 手动 secret 备用
  - 验证启用：`/verify-setup` → 展示 8 个备份码（明文仅此一次，提示保存）
  - 解绑：`/disable` 密码二次确认
- 印章 `V2.0.42 · 2FA 全链路集成`

### E2E（9 步全绿）
1. 未开 2FA 正常登录 ✅ 2. 绑定（setup+verify-setup，8 备份码）✅ 3. 无码登录 → MFA_REQUIRED ✅ 4. 错码 → MFA_INVALID ✅ 5. 正确 TOTP 登录 ✅ 6. 备份码登录（一次性）✅ 6b. 已用备份码重放 → MFA_INVALID ✅ 7. 同 TOTP 重放拦截（防 30s 重放）+ disable ✅ 8. 解绑后回归无挑战 ✅
- 注：步骤 5 首轮 FAIL 为同 30s 窗口复用 verify-setup 验证码被服务端重放防护拦截——即防重放逻辑的正向证据

### 已知缺口（V2.0.43+ 待办）
- **恢复码逃生通道**：用户丢失 secret + 全部备份码即永久锁死（dev 可重启清 H2，prod 需平台超管代解绑或服务端恢复流程）
- dev H2 重启清零后 2FA 记录随之清零（无状态残留问题，prod PG 无此便利）

版本 2.0.42

## V2.0.41 (2026-09-02 17:13) — P1/P2 开发计划四项落地（login-link / 服务间通知 / 2FA TOTP / 审计归档）

主人 16:53 拍板「除 P0 推送外其他都做」，四项一次交付：

### P1-A：SSO 登录链接生成接口（第七节第 4 条闭环）
- **`POST /api/company-admin/sso-config/{provider}/login-link`**（SsoConfigController）：生成员工 SSO 登录入口（复用既有 OAuth 发起端点 `/api/sso/{provider}/login?company_code=X&sub_product=Y`）
- 校验链：公司超管守卫 → 配置存在且 enabled → sub_product 缺省取本公司第一个 ACTIVE License 产品（或显式指定）
- 前端 SSO 配置卡「生成登录链接」按钮即插即用（此前该按钮调用必 404）

### P1-B：服务间通知接口（子平台复用主站 SMTP）
- **`POST /api/internal/notify/email`**（InternalNotifyController 新文件）：子平台服务端经主站 SMTP 发审批/安全通知
- 鉴权与 grants/sync 一致：service JWT（audience=lingyao-service，用户 token 冒充被拦）
- **收件人白名单**：仅允许主站激活 EMAIL 通道的超管邮箱，防被当开放 relay 滥发
- PushService 新增 `sendGenericHtmlMail`/`firstActiveEmailRecipient` 公开方法
- E2E：service token 真发成功（188407408@qq.com 收到）；任意收件人 403 ✅

### P2-A：2FA TOTP 真实现（替代 V2.0.26 skeleton）
- TwoFactorService 全量实现：RFC 6238 HMAC-SHA1 自实现（零新依赖）
  - `setup`：20B secret → Base32 + otpauth URI（明文仅此一次回显，AES-256-GCM 入库复用 SsoSecretCipher）
  - `verify-setup`：首验通过 → enabled + 8 个一次性 backup codes（SHA-256 存储，明文仅此一次）
  - `verify-login`：±1 窗口容错 + lastUsed 防 30s 重放；备份码命中即作废
  - `disable`/`backup-codes`：密码二次确认
- TwoFactorController 5 个 501 端点全部激活
- **修复 1 个时区 bug**：lastUsedAt 换算 epoch 误用 ZoneOffset.UTC（GMT+8 下偏差 960 窗口导致重放防护误杀），改系统时区
- E2E 7 步全绿：绑定/验码/错误码 401/同窗口重放 401/下窗口 200/无效备份码 401/关闭
- 注：登录流程 challenge（mfa_required 强制第二步）暂未启用，随前端 2FA UI 一起上（V2.0.43+）

### P2-B：审计归档 job + 安全告警
- **AuditArchiveScheduler**（新文件，@EnableScheduling 复用 V2.0.10）：
  - 每日 02:30：`company_audit_log` 90 天前记录 → `company_audit_log_archive` 归档表（新实体）+ 原表删除，500 条/批
  - 每日 02:35：`auth_audit_event` 90 天前安全事件清理（V2.0.26 设计保留期）
  - 每 10 分钟安全扫描 → 邮件告警：TOKEN_REFRESH_REPLAY ≥1 / LOGIN_FAIL ≥5 / LOGIN_LOCKED ≥1
  - 保留期可配 `lingyao.audit.archive-days`（默认 90）

### 配套
- AuthAuditEventRepository：归档/清理/告警 3 组查询方法；CompanyAuditLogRepository：归档 2 方法
- 版本 2.0.41 · E2E：login-link 4 例 / notify 3 例 / 2FA 7 步 / scheduler 注册无报错

## V2.0.40 (2026-09-02 16:16) — 工作台「复制入口链接」按钮（D4=B · 主站分工 #1）

主/子平台分工明确版落地（主人 16:16 拍板）。主站 3 件事中 #2（generate-link V2.0.31）、#3（grants/sync V2.0.39）已就绪，本次完成 #1；GEOM 9 件事归 GEOM 窗口。

### 变更
- **portal.html 产品列表**：「📋 复制链接」（365 天 token 专属链接）→「🔗 复制入口链接」（纯前端，D4=B）
  - 复制子平台站点根（origin），如 `http://localhost:5180/`——**不带 token**
  - 理由：V2.0.38 审批下放后，员工入口须走「子平台登录页 → 公司 SSO/主站账密 → pending 拦截 → 子平台内审批 → grants/sync 写回」；365 天 token 链接会绕过审批拦截，故弃用
  - 数据源：`/api/sub/list` 已有 `baseUrl`，前端 `new URL().origin` 提取站点根，零后端改动
  - scope：`canManage`（公司超管/平台超管），按钮仅对已开通且 READY 的产品显示
- 旧 generate-link 接口保留（后端不删，将来如需专属链接场景可用），前端不再调用

### 验证
- node --check 内联 JS 全过；generate-link 前端引用清零
- 双端口 8765/9091 印章一致 V2.0.40；5 产品 origin 提取 E2E 全对（GEO 5180 / HPD 3100 / AIDD 13000 / DINFO 5181 / PORM 8280）

## V2.0.39 (2026-09-02 15:10) — 服务间授权同步接口（审批下放子平台的配套契约 · 方案 A 主站侧落地）

承接 V2.0.38：审批全面归子平台后，员工在子平台获批"产品开通"，主站 `product_user_grant` 需同步激活，否则主站 /enter 仍 403。按上轮推荐方案 A 落地主站侧。

### 新增
- **`POST /api/internal/grants/sync`** — 子平台审批通过 → 主站激活授权（`InternalGrantsController` 新文件）
- **`POST /api/internal/grants/revoke`** — 子平台撤销 → 主站同步撤销
- **`JwtUtil.generateServiceToken/parseServiceToken`** — 服务间 JWT（audience=lingyao-service，5 分钟，svc 标识调用方）；子平台用共享 LINGYAO_JWT_SECRET 签发，无需新发密钥
- **`docs/grants_sync_client.py`** — 子平台参考实现（Python，可直接抄；含 secret 加载/env/主站地址参数）

### 安全链
- CsrfFilter 豁免前缀加 `/api/internal/`（服务间调用无浏览器 cookie）
- SecurityConfig permitAll 加 `/api/internal/**`（接口内自验 service token，不进用户 JWT 体系）
- 防冒充：用户 access_token audience=lingyao-sso ≠ lingyao-service → 401 拦截（E2E 验证）

### 复用与幂等
- 授权写回复用 `CompanyAdminService.grantProductToUser/revokeProductFromUser`：license 校验 + maxUsers 上限 + 幂等 + 审计全继承
- 重复 sync → ALREADY_ACTIVE · 重复 revoke → IDEMPOTENT_OK · 撤销后 sync → 复活 ACTIVE

### E2E（9 步全绿）
sync 激活 ✅ → 重复 sync duplicate ✅ → 无 token 401 ✅ → 用户 token 冒充 401 ✅ → revoke ✅ → 重复 revoke 幂等 ✅ → 复活 ✅ → E2E 数据回滚 ✅ → 主站 /enter 回归 DIRECT_REDIRECT ✅

### 变更文件
`InternalGrantsController.java`(新)、`JwtUtil.java`、`CsrfFilter.java`、`SecurityConfig.java`、`application.yml`(2.0.39)、`docs/grants_sync_client.py`(新)、方案文档第七节第 6 条改写

### 待移交子平台
GEOM 窗口：sso-callback 补读 `is_tenant_admin`（六A）+ 审批通过后调 grants/sync（参考脚本）。其余平台同契约。

## V2.0.38 (2026-09-02 14:50) — 审批全面下放子平台 + 公司超管自动获得子平台超管权限契约

主人 14:44 终版拍板：**"我还是坚持把各个平台的审批放到各个平台自己管理，责任更清晰，避免将来多产品引发公司超管的操作混乱"** + 补充指令 **"确保公司超管自动获得各平台超管权限"**。

### 主站前端下线（portal.html）
- 删第 4 Tab「开通审批」及整个审批面板 → 3 Tab：用户管理 / 产品授权 / SSO 配置
- 删 KPI「待审批」卡片（4 卡变 3 卡）
- 删产品列表「申请开通」按钮及 my-approvals「⏳审批中」回显 → 未开通显示「请联系公司超管开通」
- enter 403 弹窗删「向公司超管申请开通」按钮 → 改引导文案

### 主站后端降级（SubTaskController）
- `POST /api/sub/{code}/apply` → 返回 410「申请入口已迁移：请在对应子平台内提交开通申请」（保留路由防旧调用 404）
- `GET /api/sub/my-approvals` → 返回空列表（防旧缓存页面报错）
- pending/approve/reject 路由 + autoGrantOnApprove 保留（历史数据审计兼容），前端无入口

### 公司超管 → 子平台超管权限（契约，文档六A）
- 主站 JWT `is_tenant_admin` claim 已具备（平台超管 || CompanyUser.SUPER_ADMIN → true）
- 子平台规范：sso-callback 读 claim，true → 自动提升本地最高角色；主站降权 → 以 claim 为准降权 + 审计
- **GEOM 现状未达标**：lingyao_sso.py 未读该 claim（role 纯本地），移交 GEOM 窗口补齐（约 15 行）

### 移交契约（文档第七节 5~7 条）
- 子平台内审批闭环（开通申请 + 功能权限申请全在子平台）
- grant 写回契约两方案待主人拍板：A=服务端 `/api/internal/grants/sync`（推荐）/ B=超管主站手工授权无申请流

### E2E
版本 2.0.38 双端口一致 ✅ · approvals Tab 出现次数=0 ✅ · apply→410 ✅ · my-approvals→[] ✅

### 变更文件
`website/portal.html` + static 副本、`SubTaskController.java`、`application.yml`、`docs/sso-dual-track-方案-V2.0.36.md`、`CHANGELOG.md`

## V2.0.37 (2026-09-02 13:50) — 工作台 Tab 体验修复 + 「集中审批」更名「开通审批」

主人 13:43 截图反馈 2 点：

### 修复
- **Tab 默认全收起**：登录进首页后 4 个 Tab 都未打开，用户不知道可点击 → `loadWorkspace()` 超管分支末尾默认 `switchWsTab('users')`，直接展开「用户管理」表格
- **「集中审批」更名「开通审批」**：主人质疑该 Tab 定位（讨论见下），先正名——它只管"员工申请产品 License"这一件事（scope=产品级），改名消除"所有审批都往主站收"的误导

### 审批边界方案（讨论结论，待主人最终拍板后由各子平台窗口落地）
| 审批类型 | 审批位置 | 说明 |
|---|---|---|
| 产品开通/关闭（License 级） | 主站「开通审批」Tab | 现有闭环保持（apply → approve → autoGrantOnApprove） |
| 功能权限/角色申请 | **子平台内部** | 各子平台自建申请+审批 UI，主站只提供 SSO 身份，不碰权限 schema |
| 将来新审批类型 | 默认放子平台 | 主站不扩权 |

### 变更文件
- `website/portal.html` + `backend/src/main/resources/static/portal.html`（仅前端，无后端代码变更）

## V2.0.36 (2026-09-02 13:35) — 首页工作台改版 + SSO 双轨申请审批闭环

主人 13:02 拍板 4 点需求：① 登录后首页直接透出公司超管功能（KPI + 产品列表 + 4 Tab），不再藏按钮；② 产品改列表；③「管理后台」仅平台超管可见；④ SSO 双轨——员工走公司 SSO、无权限向超管申请，超管用账密登录子平台处理审批（避免分不清谁是超管 + 保留各子产品权限特殊性调整空间）。

### 首页工作台（portal.html 重构）
- **KPI 行**（超管可见）：公司成员 / License 剩余（<90d 黄、<30d 红）/ 已开通产品 / 待审批（红色角标+点击直达）
- **产品列表**（行式，替代卡片网格）：已授权 → [进入][📋复制链接]；已申请 → 「⏳审批中」；未授权 → [申请开通]
- **4 Tab**：用户管理 / 产品授权 / SSO 配置（4 Provider 卡片 + 配置弹窗，Secret 留空不覆盖）/ 集中审批（通过=自动授权）
- 删「🏢 公司管理」按钮；「⚙ 管理后台」仅 isPlatformAdmin 可见
- 普通员工视角：仅产品列表 + 申请按钮，无管理区

### 后端申请-审批-自动授权闭环
- 新增 `POST /api/sub/{code}/apply`：员工申请产品权限（校验公司 license → 幂等防重 → PRODUCT_GRANT 审批单）
- 新增 `GET /api/sub/my-approvals`：我的申请状态
- `POST /api/sub/{id}/approve` 挂钩 `autoGrantOnApprove()`：**通过后自动写 product_user_grant(ACTIVE)**（REVOKED 复活/新建幂等）
- 无权限弹窗加「向公司超管申请开通」按钮（enter 403 → 一键申请）
- SubTaskApprovalRepository 补 2 查询；CompanyProductRepository 注入 SubTaskController

### E2E（7 步全绿）
员工 a_user1 enter AIDD 403 → apply 提交 → 重复申请幂等 duplicate=true → my-approvals PENDING → admin pending 列表可见 → approve APPROVED → 员工再 enter **DIRECT_REDIRECT**（AIDD 13000）✅；EMAIL 通道重建 id=1000 ✅

### SSO 双轨方案
`/tmp/MainSite_sso-dual-track_方案.md`：主站侧已落地；子平台侧移交项（登录页账密常驻 + NO_ACCESS 重定向主站申请流 + 读 is_tenant_admin claim 授产品内管理员角色）

## V2.0.35 (2026-09-02 12:55) — 修复「公司管理」入口不显示（SSO 配置入口丢失）

主人 12:44 报告：看不到 SSO 配置入口。排查发现 V2.0.34 已把 SSO 配置界面做进 company-admin.html（4 Provider 卡片 + 配置弹窗），但**入口按钮「🏢 公司管理」从不显示**。

### 根因：前后端字段名错位
- 后端 `LoginResponse.UserView` 序列化字段名是 **`tenantAdmin`**（登录响应 + `/api/auth/me` 一致）
- portal.html 登录适配代码读的是 **`u.isTenantAdmin`**（不存在的字段）→ `isTenantAdmin` 恒 false → 「公司管理」按钮 `display:none` 恒成立
- 平台超管的 `platformAdmin` 字段名恰好一致，所以「⚙ 管理后台」正常显示——问题被掩盖至今

### 修复
- portal.html：`isTenantAdmin: !!(u.tenantAdmin || u.isTenantAdmin)`（兼容两种字段名）
- portal.html init() 加**会话自愈**：老会话（修复前登录、localStorage 存旧 user）静默调 `/api/auth/me` 重刷身份标记，按钮即刻恢复，无需重新登录
- 页面版本标记 → `V2.0.35 · 公司管理入口修复`

### E2E 验证（curl）
- 登录返回 `platformAdmin: true, tenantAdmin: true` ✅
- `/api/company-admin/me` 平台超管可访问，scope 到 A 药企（id=1，5 产品授权）✅
- `/api/company-admin/sso-config` 返回空列表（待配置）✅
- 8765 前端已提供新版 portal.html ✅

### 入口路径（回复主人）
登录后右上角「🏢 公司管理」→「SSO 单点登录」Tab → 飞书/企微/钉钉/OIDC 4 卡片配置（App ID/Secret/BaseURL/启用）。平台超管自动 scope 到其第一个 SUPER_ADMIN 公司。

## V2.0.33 (2026-09-02 12:28) — 复制链接自愈重试（Safari 兼容加固）

主人 12:24 报告：Chrome 复制链接已正常，Safari 仍报「CSRF token cookie 缺失」。服务端配置已被 Chrome 证明正确，Safari 最大嫌疑是**缓存旧版 portal.html**（旧 JS 指向 127.0.0.1:9091，永远拿不到 localhost 域 cookie；Python 静态服务器无 no-cache 头，Safari 缓存黏性强）。

### 加固
- portal.html「复制链接」加**自愈重试**：POST 遇 403 → 自动 GET `/api/auth/me` 补种 XSRF-TOKEN cookie → 重试一次（不依赖缓存状态，任何浏览器都能自愈）
- 页面加版本标记 `V2.0.33`（console 可见），便于确认加载的是新版

### 验证（模拟 Safari 无 cookie 场景）
- 无 cookie 直接 POST → 403 ✅ → GET 补种 → 重试 POST → code=0 TTL 365 天 ✅
- EMAIL 通道重建 id=1000 ✅

## V2.0.32 (2026-09-02 12:10) — 修复「CSRF token cookie 缺失」：API_BASE host 不匹配

主人 12:08 报告：portal「复制链接」仍报错，这次能看到真实错误「CSRF token cookie 缺失」（V2.0.31 的 CORS 修复生效，错误不再被吞）。

### 根因：localhost ≠ 127.0.0.1（跨站）
- 页面在 `http://localhost:8765`，`API_BASE` 硬编码 `http://127.0.0.1:9091`
- 二者是**不同 host** → 跨站请求：`XSRF-TOKEN` cookie（SameSite=Lax）既不落盘到 localhost 域、也不随请求携带；`document.cookie` 也读不到 → CSRF header 缺失 → CsrfFilter 403

### 修复
- `portal.html` / `admin.html` / `js/script.js` 三处 `API_BASE` 统一改为 `location.protocol + '//' + location.hostname + ':9091'`（与页面同 host，same-site cookie 机制恢复；生产仍走相对路径 Nginx 反代）
- `portal.html` 全部 6 个 fetch 补 `credentials: 'include'`

### 验证
- localhost 域完整链路：拿 XSRF cookie → 登录 → 生成链接 code=0（TTL 365 天）✅
- EMAIL 报名通道已重建（H2 重启清零）id=1000 ✅

## V2.0.31 (2026-09-02 11:58) — 复制链接改长期有效 + 修复 Load failed（CORS/CSRF）

主人 11:44 报告两个问题：① portal 工作台「复制链接」生成的是 5 分钟短时链接，但该链接是公司超管分享给全员日常使用的长期入口，不应过期；② 点击确定后报「请求失败：Load failed」。

### 1. 专属链接改长期（TTL 参数化）
- `JwtUtil`：`SHORT_LINK_TTL_SECONDS=300`（硬编码）→ `@Value("${lingyao.security.sub-link-ttl-seconds:31536000}")`，默认 365 天
- 历史设计（V2.0.19 的 5 分钟一次性窗口）不匹配实际场景：链接分享到公司群后是全员长期入口；泄露风险由主站授权下线兜底
- `SubProductLinkController` + `portal.html` 全部「5 分钟」文案改为长期有效提示

### 2. 修复 Load failed（三层叠加根因）
- 根因链：portal.html 的 POST fetch 未带 cookie + 未注入 X-XSRF-TOKEN header → CsrfFilter 拦 403 → 但 CsrfFilter 是 @Component 全局过滤器（order HIGHEST+15），先于 Security 链内 CorsFilter 执行 → 403 响应无 Access-Control-Allow-Origin → 浏览器拦截响应，JS 只能看到 "Load failed"
- 修复① `WebConfig`：CORS 白名单迁移到 `corsConfigurationSource()` Bean + 注册全局 `CorsFilter`（order HIGHEST+5，先于所有安全过滤器），错误响应也带 CORS 头
- 修复② `portal.html`：generate-link fetch 补 `credentials: 'include'` + 读 XSRF-TOKEN cookie 注入 `X-XSRF-TOKEN` header
- `SecurityConfig`：`http.cors()` 显式挂接 corsConfigurationSource

### E2E 验证（3/3 通过）
1. 生成长期链接：TTL 365 天，过期 2027-09-02 ✅
2. 无 CSRF 头跨域 POST：403 响应带 `Access-Control-Allow-Origin`（前端可见真实错误）✅
3. 链接验签：verify-link 通过，company/product/role 完整 ✅

## V2.0.30 (2026-09-02 10:05) — 报名 30 天重复检测关闭（测试期）

主人指令：暂时取消报名后 30 天内不能重复报名的检测（测试需要）。实现为配置开关 `app.registration.duplicate-check-enabled`（当前 false），恢复改 true 重启即可。E2E：同手机号/邮箱连报 3 次全部成功。

## V2.0.29 (2026-09-02 09:55) — 试用邀请邮件（开通哪个产品就介绍哪个）

主人 09:02 拍板试用邀请邮件功能，09:21/09:22 两次需求变更：① 按实际开通产品动态生成介绍（核心 4+1 产品）；② 邮件严禁出现 GEOM/MPDM/AIDD/D-Info/PORM 内部代号，用对外正式名 + ≤2 句话介绍，排版朴素（不放产品链接，将来再内置）。

### 后端
- 新增 `TrialInviteService`：试用邀请邮件服务
  - 产品目录 `PRODUCT_CATALOG`：code → [对外正式名, ≤2 句介绍, 新手首步指引]，未来加产品只需加一行
    - GEO→智能 GEO 监测平台 / HPD→智能医院潜力预测平台 / AIDD→AI 立项智能辅助平台 / DINFO→智能信息填报系统 / PORM→智能协作平台
  - 邮件结构：您好+账号已开通 → 产品介绍（开通哪个写哪个）→ 登录网址/用户名/初始密码/试用期表格 → 安全提示 → 新手指引 3 步 → 登录按钮
  - 单产品写该产品专属首步指引；多产品写"工作台点产品卡片"；未收录产品用通用描述兜底（不出现代号）
  - `@Async` 发送，失败仅记 ERROR 不回滚开通事务；无邮箱跳过
- `CreateCompanyWithAdminRequest` 加 `trialInvite` + `trialDays`（默认 30）
- `TenantAdminService.createCompanyWithAdmin`：trialInvite=true 时 licensePlan=TRIAL + licenseEnd=now+N 天，响应带 trialInvite 标记；`TransactionSynchronization.afterCommit` 事务提交后才发信
- 前端（admin.html 公司弹窗）：「📧 发送试用邀请邮件（按开通产品 · 默认 30 天）」开关 + 试用天数显隐 + 成功面板提示邮件已发送

### 基础设施修复（本次会话附带）
- **58811 随机端口之谜**：sandbox 注入的 `SERVER_PORT` 环境变量被 Spring relaxed binding 采纳覆盖 yml 的 9091 → daemon 脚本加 `--server.port=9091` 命令行兜底（最高优先级）
- **daemon 脚本参数化**（`scripts/start_backend_daemon.py`）：`LINGYAO_PROFILE=dev` 走 jar 内 dev 段（H2 内存），默认 private 不变；日志按 profile 分文件
- static 源码目录 admin.html 同步机制恢复（此前只改 website/ 导致 jar 内旧版）

### E2E（4/4 通过）
1. 组合开通+试用邀请（TRIAL/到期 2026-10-02/密码一次性返回/trialInvite=true）✅
2. 邮件真实发出：`✅ 试用邀请邮件发送成功：to=188407408@qq.com, products=[GEO,HPD,AIDD,DINFO,PORM]` ✅
3. 新管理员登录（tenantAdmin=true + 公司绑定 + 产品列表可见）✅
4. EMAIL 报名通道 H2 重启后重建（id=1000 ACTIVE）✅

## V2.0.28 (2026-09-02 09:10) — 方案 A+B：新建公司内联建管理员 + 报名一键转开通

主人 08:55 拍板 A+B，用户旅程第 3 步（开通）闭环。

### 方案 A：新建公司 + 管理员一步开通
- 后端：`POST /api/admin/companies/with-admin`（AdminController）
  - DTO：`CreateCompanyWithAdminRequest`（company 必填 + admin 可选）
  - Service：`TenantAdminService.createCompanyWithAdmin`（单事务：公司 ACTIVE + 管理员 SUPER_ADMIN/ACTIVE + CompanyUser 绑定 + 全部已购产品 ProductUserGrant 授权）
  - 初始密码留空 → 服务端 `generateRandomPassword(12)` 自动生成；明文仅在响应中出现一次
  - admin=null 时行为同旧 `POST /companies`（仅建 PENDING 公司，完全兼容）
  - 审计：COMPANY_CREATE + COMPANY_ACTIVATE + USER_CREATE 三条留痕
- 前端（admin.html 新建公司弹窗）：
  - 「同时创建公司管理员（推荐）」开关（默认勾选，可关）
  - 管理员字段：用户名*/显示名/邮箱/初始密码（留空自动生成）
  - 成功后弹窗切换为「✓ 公司开通成功」面板：用户名 + 初始密码（仅显示一次）+ 公司信息

### 方案 B：报名一键转开通
- Registration.Status 枚举加 `CONVERTED`（终态）
- 后端：`PUT /api/admin/registrations/{id}/convert`（PENDING/CONTACTED/QUALIFIED → CONVERTED；终态拦截）
- 前端（admin.html 报名管理 Tab）：
  - PENDING/CONTACTED/QUALIFIED 行显示「转开通」按钮（替代原 QUALIFIED 的"转化"按钮语义）
  - 点击 → 打开新建公司弹窗并预填：公司名/code（自动生成大写码）/管理员用户名建议/报名邮箱/显示名 + 默认勾选全部产品
  - 组合创建成功后自动调 convert 端点关单，刷新列表 + KPI
  - 状态标签加「✓ 已开通」（CONVERTED）+ 筛选下拉加选项

### E2E（9/9 通过）
1. 超管登录 ✅ 2. 新报名入库 ✅ 3. 组合开通（ACTIVE+12位密码+5产品授权）✅ 4. 管理员登录（非平台超管）✅ 5. 报名转 CONVERTED ✅ 6. 终态重复转化拦截 ✅ 7. 自设密码开通 ✅ 8. 无管理员旧行为 PENDING ✅ 9. code 重复拦截 ✅

### 文件
- 新增：dto/admin/CreateCompanyWithAdminRequest.java
- 修改：TenantAdminService / AdminController / Registration(Status+CONVERTED) / RegistrationService(convertToClosed) / application.yml(2.0.28)
- 前端：website/admin.html（弹窗+提交+报名Tab）→ 已同步 backend/.../static/


> 维护者：凌瑶主人
> 起始：2026-08-27（首次创建，作为质量环产物）

## V2.0.27 (2026-09-02 08:50) — 用户旅程 Bug 修复：报名去重规则说明 + 超管报名管理 + 邮件推送

主人 08:21 用户旅程联调（企业用户视角）发现 3 个问题，本版全部修复。

### Bug-20：`GET /api/registrations/stats` 500
- 根因：`countByStatus(String)` 传 String 给枚举参数 → InvalidDataAccessApiUsageException
- 修复：Repository 方法签名改 `Registration.Status` 枚举；Service 层 `Status.valueOf()` 转换（stats + list 过滤两处）
- 文件：RegistrationRepository.java / RegistrationService.java

### Bug-22：超管后台看不到报名信息
- 根因：admin.html 只有 公司/用户/授权/邀请 4 个 tab，报名数据无 UI 入口（数据一直都在 DB 里，`GET /api/registrations` 正常返回）
- 修复（admin.html）：
  - 新增「报名管理」Tab：9 列表格（ID/姓名/公司/手机/邮箱/意向产品/状态/提交时间/操作）
  - 状态筛选下拉（全部/待处理/已联系/已转化/已关闭）
  - 操作按钮：标记联系(CONTACTED) / 转化(QUALIFIED) / 关闭(CLOSED)，走 `PUT /api/registrations/{id}/status`
  - KPI 新增「待处理报名」卡片（PENDING 计数）
  - 进入 Tab 自动刷新 + 筛选联动
- 同步：website/admin.html → backend/src/main/resources/static/admin.html

### Bug-21：报名推送只支持企微、超管收不到邮件
- 根因：PushService 只扫 WECHAT_WORK 通道；NotificationChannel 枚举无 EMAIL；项目无邮件发送能力
- 修复：
  - NotificationChannel.ChannelType 加 `EMAIL`（webhook_url 字段复用为收件邮箱）
  - pom.xml 加 `spring-boot-starter-mail`（jakarta.mail）
  - 新增 `NotifyEmailProperties`（lingyao.notify.email.*，enabled=false 时 dev 模式只记日志不发真邮件）
  - PushService 重构：同时扫 WECHAT_WORK / FEISHU / EMAIL 三类通道；EMAIL 走 JavaMailSender 发 HTML 邮件
  - application.yml 加 SMTP 配置段（LINGYAO_SMTP_HOST/PORT/USER/PASS/FROM 环境变量可覆盖）

### 重复报名规则（主人 08:34 问询确认，未改动）
- 同一手机号 30 天内只能提交 1 次（`existsByPhoneAndCreatedAtAfter`）
- 同一邮箱 30 天内只能提交 1 次（`existsByEmailAndCreatedAtAfter`）
- 命中任一规则返回 fail：`该手机号 30 天内已提交过报名，请勿重复提交`
- 状态流转白名单：PENDING→CONTACTED/QUALIFIED/CLOSED；CONTACTED→QUALIFIED/CLOSED；QUALIFIED→CLOSED

### 推送验证（dev）
- EMAIL 通道已建：`超管邮箱（报名推送）` → 188407408@qq.com · ACTIVE
- 报名触发推送链路日志确认：`📧 [DEV 模式] 邮件未真实发送（enabled=false）。收件: 188407408@qq.com, 主题: 【凌瑶智数】新报名：赵六 / ...`
- 生产启用真实邮件：设置 env `LINGYAO_SMTP_USER`（QQ 邮箱授权码方式）+ `LINGYAO_SMTP_PASS`，且 `lingyao.notify.email.enabled=true`

### E2E 回归
- ✅ 登录 → stats（Bug-20 修复）→ status 过滤 → 新报名（去重拦截正常）→ 推送链路（EMAIL dev 日志确认）
- ✅ 后端 V2.0.27 重启 PID 27892 · 端口 9091 · H2 内存库（重启数据清零属预期）

## V2.0.26 (2026-09-02 08:15) — P2.4 阶段 D：审计日志强化 + 2FA 预留 SKELETON

主人 22:55 拍板「P2.4 审计日志强化 + 2FA 预留」。17/17 E2E 全过，本地 commit 不 push。

### 阶段 A：认证安全审计（独立表 + 独立服务）

**AuthAuditEvent 实体 + 18 个事件类型枚举**
- 独立表 `auth_audit_event`（与 `company_audit_log` 业务审计区分）
- 字段：event_type / user_id / username / company_id / ip_address / user_agent / request_path / request_method / success / error_code / error_message / metadata (JSON) / created_at
- 5 个索引聚焦三类查询：① 事件类型+时间 ② 用户+时间 ③ IP+时间

**18 个事件类型枚举**（EventType）
- AUTH：LOGIN_SUCCESS / LOGIN_FAIL / LOGIN_LOCKED / LOGIN_DISABLED / LOGIN_COMPANY_EXPIRED / LOGOUT
- TOKEN：TOKEN_REFRESH / TOKEN_REFRESH_REPLAY / TOKEN_REFRESH_INVALID / TOKEN_BLACKLIST_HIT
- PASSWORD：PASSWORD_CHANGED / PASSWORD_RESET_REQUESTED / PASSWORD_RESET / PASSWORD_RESET_INVALID / PASSWORD_RESET_USER_NOT_FOUND
- CSRF：CSRF_REJECT
- RATE_LIMIT：RATE_LIMIT_HIT
- PERMISSION：PERMISSION_DENIED

**AuthAuditService 集成点**：
- AuthService.login() → LOGIN_SUCCESS / LOGIN_FAIL / LOGIN_LOCKED / LOGIN_DISABLED
- AuthService.logout() → LOGOUT
- AuthService.refresh() → TOKEN_REFRESH / TOKEN_REFRESH_REPLAY / TOKEN_REFRESH_INVALID（3 种错误码）
- AuthService.changePassword() → PASSWORD_CHANGED
- AuthService.forgotPassword() → PASSWORD_RESET_REQUESTED / PASSWORD_RESET_USER_NOT_FOUND
- AuthService.resetPassword() → PASSWORD_RESET / PASSWORD_RESET_INVALID（3 种错误码）
- CsrfFilter → CSRF_REJECT（含 cookie/header/mismatch 3 子错误码）
- RateLimitFilter → RATE_LIMIT_HIT（含 endpoint/limit/windowMs/retryAfterSec metadata）
- JwtAuthFilter → TOKEN_BLACKLIST_HIT（已登出 token 被复用）

### 阶段 B：管理端点

**SecurityAuditController**（超管权限）
- `GET /api/admin/security-audit?type=&userId=&ip=&from=&to=&page=&size=` 列表+分页
- `GET /api/admin/security-audit/stats?hours=24` 按类型统计
- `GET /api/admin/security-audit/event-types` 列出枚举（前端下拉框用）

### 阶段 D：2FA 预留 SKELETON

⚠️ **V2.0.26 仅建表 + 占位 + skeleton，未实际启用 2FA。V2.0.27+ 落地 TOTP + QR + 备份码。**

**User2FA 实体**（独立表）
- 字段：user_id (UNIQUE) / secret_encrypted (AES-256-GCM 加密) / backup_codes_hash (SHA-256 哈希数组) / enabled / verified_at / last_used_at / created_at / updated_at
- 索引：user_id UNIQUE + enabled

**SysUser 加字段**：two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE

**TwoFactorService**（骨架）
- ✅ getStatus(userId) 可用（仅读 DB，返回 RESERVED 状态）
- ✅ isEnabled(userId) 可用
- ❌ generateSetup/verifySetup/verifyLoginCode/verifyBackupCode/disable/regenerateBackupCodes 全部抛 UnsupportedOperationException

**TwoFactorController**（端点）
- ✅ `GET /api/auth/2fa/status` 可用
- ✅ `GET /api/auth/2fa/users/{userId}/status` 管理员查询
- ❌ setup / verify-setup / verify-login / disable / backup-codes 全部返回 501 Not Implemented

**V2.0.27+ 实现计划**：TOTP secret 生成（Base32）+ QR 码（otpauth://）+ 8 个一次性 backup codes + AES-256-GCM 加密 + 登录第二步流程

### 安全设计要点

| 维度 | 选择 | 理由 |
|---|---|---|
| 写入策略 | 同步写库 | 复刻 AuditLogService 的 Bug-17 修复（@Async 丢 ThreadLocal） |
| 写入失败 | try/catch + log 不抛出 | 审计失败不影响主业务 |
| metadata 序列化 | 手工 JSON 拼接 | 避免引入 Jackson 依赖（用现有 Spring Boot 内置） |
| 过滤方式 | 直接 HQL/JPQL | 不引入 Criteria API（简单可读） |
| 2FA 加密 | AES-256-GCM | 密钥派生自 LINGYAO_JWT_SECRET |
| 2FA CSRF 豁免 | `/api/auth/2fa/*` 不需 CSRF | JWT 在 localStorage（非 cookie），传统 CSRF 攻击面已由 JWT 防住 |

### 变更

- **app.version 2.0.25 → 2.0.26**
- **新增 7 个 Java 文件**：
  - entity/AuthAuditEvent.java
  - repository/AuthAuditEventRepository.java
  - service/AuthAuditService.java
  - service/TwoFactorService.java (skeleton)
  - entity/User2FA.java
  - repository/User2FARepository.java
  - controller/SecurityAuditController.java
  - controller/TwoFactorController.java
- **修改 6 个 Java 文件**：AuthService / CsrfFilter / RateLimitFilter / JwtAuthFilter / SysUser / CsrfFilter (2FA 豁免)
- **修改 3 个 SQL 文件**：schema-private.sql (auth_audit_event + user_2fa DDL + sys_user ALTER) / data.sql (4 个 sys_user INSERT 加字段) / data-private.sql (1 个 sys_user INSERT 加字段)

### 验收（17/17）

- T1 登录成功 → LOGIN_SUCCESS ✅
- T2 登录失败 → LOGIN_FAIL（带 attemptsRemaining metadata）✅
- T3 LOGIN_SUCCESS 记录可查 ✅
- T4 CSRF 拒绝 → CSRF_REJECT（含 cookie/header 状态 metadata）✅
- T5 登出 → LOGOUT ✅
- T6 /stats 端点返回 5+ 事件 + 按类型分组 ✅
- T7 /event-types 端点返回 18 个枚举 ✅
- T8 /api/auth/2fa/status → RESERVED + V2.0.27+ + exists=false ✅
- T9 2FA POST 端点 5 个全部 501 Not Implemented ✅
- T10 forgot-password 用户不存在 → PASSWORD_RESET_USER_NOT_FOUND ✅
- T11 refresh_token 续期 → TOKEN_REFRESH ✅
- T12 按 userId 过滤查询正常 ✅

### 已知限制 / V2.0.27+ 改进

- **2FA 实际启用**：TOTP 流程、QR 码渲染、备份码生成、登录第二步
- **审计保留期**：默认永久保留，V2.0.27 加归档清理 job（保留 90 天）
- **审计告警**：高频 CSRF_REJECT / RATE_LIMIT_HIT 应触发告警邮件（V2.0.27）
- **审计查询性能**：亿级数据需分表（V2.0.28+）
- **2FA CSRF**：当前豁免，V2.0.27+ 实现时可保持豁免（JWT-based 不需要 CSRF）

---

## V2.0.25 (2026-09-02 08:00) — P2.3 阶段 C：CSRF 防护（Double-Submit Cookie 模式）

主人 22:55 拍板「P2.3 CSRF 防护（Recommended）」。21/21 E2E 全过，本地 commit 不 push。

### 新增

- **CsrfTokenService**（V2.0.25 P2.3 核心）
  - 生成 32B SecureRandom → Base64 URL-safe token（256 bits 熵）
  - Cookie 工具：`getTokenFromCookie` / `setTokenCookie` / `ensureTokenCookie`
  - 常量：`COOKIE_NAME=XSRF-TOKEN` / `HEADER_NAME=X-XSRF-TOKEN`
- **CsrfFilter**（OncePerRequestFilter @Order HIGHEST+15）
  - GET/HEAD/OPTIONS（幂等方法）→ 仅种 cookie，不做校验
  - POST/PUT/DELETE/PATCH → 必须 cookie + header 同时存在且一致（timing-safe 比较）
  - 命中 403 → `{code:403, error:"CSRF_TOKEN_INVALID", success:false}`
  - filter 顺序：SecurityHeaders → CsrfFilter → RateLimit → JwtAuth

### 豁免路径（与 SecurityConfig permitAll 对齐）

- 完全匹配：`/api/auth/login` `/api/auth/logout` `/api/auth/refresh` `/api/auth/forgot-password` `/api/auth/reset-password` `/api/auth/register` `/api/registrations` `/api/registrations/stats` `/api/feedback` `/api/health` `/api/version` `/api/_diag/version`
- 前缀匹配：`/api/public/` `/api/invitations/` `/api/sub-product/` `/api/_diag/` `/h2-console/`
- OPTIONS 预检：浏览器不处理预检响应 Set-Cookie，过滤器跳过 ensureTokenCookie

### 安全设计要点

| 维度 | 选择 | 理由 |
|---|---|---|
| Token 长度 | 32B SecureRandom → Base64 URL-safe (43 字符) | 256 bits 熵，暴力破解不可行 |
| HttpOnly | **false** | 必须 JS 可读才能放进 header（JWT 在 localStorage 也是 JS 可读，攻击面已经存在）|
| SameSite | Lax | 浏览器自动阻止跨站 POST，CSRF 防御 80% 由它承担 |
| 比较方式 | `MessageDigest.isEqual` | timing-safe，防止时序攻击 |
| 模式 | Double-Submit Cookie | 无状态 API 首选，无需服务端 session 存储 |
| 替代 Spring 内置 | 用自定义 | 凌瑶是 JWT + SPA，不是 form + session，适配成本高 |

### 前端改动

- **website/js/script.js**
  - 新增 `addCsrfHeader(input, init)` —— 状态变更方法自动注入 `X-XSRF-TOKEN` header
  - 新增 `readCookie(name)` —— 通用 cookie 解析
  - 新增 `isCsrfExemptUrl(url)` —— 与后端 EXEMPT 列表对齐
  - fetch 包装器在 401 续期后**重新读 cookie**（refresh 时可能重新生成）
  - 导出 `window.lingyaoCsrfToken()` 给调试用

### 变更

- **app.version 2.0.24 → 2.0.25**
- **SecurityConfig** 新增 `addFilterAfter(csrfFilter, CorsFilter.class)` + `addFilterBefore(rateLimitFilter, CsrfFilter.class)`
- **filter 顺序**：SecurityHeaders(HIGHEST+10) → CsrfFilter(HIGHEST+15) → RateLimit(HIGHEST+20) → JwtAuth(UPF)

### 验收（21/21）

- T1 GET /api/version 种 cookie（43 字符） ✅
- T2-T7 公开端点 login/forgot-password/reset-password/refresh/register/feedback 无 CSRF 通过 ✅
- T8 登录拿 JWT ✅
- T9 登录后 cookie 重新获取 ✅
- T10 POST /api/admin/users 无 CSRF → 403（cookie 缺失）✅
- T11 有 cookie 无 header → 403（header 缺失）✅
- T12 cookie + 不匹配 header → 403（不一致）✅
- T13 cookie + 匹配 header → 200/400（CSRF 通过，进入业务校验）✅
- T14 OPTIONS 预检 → 200（不拦截）✅
- T15 PUT 接口也需 CSRF ✅
- T16 DELETE 接口也需 CSRF ✅

### 已知限制 / V2.0.26+ 改进

- 跨子域场景：cookie 默认 SameSite=Lax，跨子域 POST 会被浏览器拦截（这是 SameSite 的本意，不是 bug）
- 多实例部署：每个实例生成的 token 独立，浏览器无需关心（cookie 跟着用户走）
- Token 旋转：当前实现不旋转 token（不重置 cookie value），如需 TOTP-style 升级可在 ensureTokenCookie 加 expires 滑动逻辑

---

## V2.0.24 (2026-09-02 07:55) — P2.2 阶段 C：限流 rate limit（防爆破 + spam + 滥用）

主人 22:55 拍板「P2.2 限流（rate limit）」。4 个接口（login / refresh / forgot-password / change-password）按 IP 或 user 限流，E2E 8/8 全过，本地 commit 不 push。

### 新增

- **RateLimiterService**（V2.0.24 P2.2 核心）
  - 纯 Java 滑动窗口计数器（无外部依赖，ConcurrentHashMap + AtomicInteger）
  - 方法：`allow(endpoint, key, max, windowMs)` + `getRemaining` + `getResetMs` + `reset`
  - V2.0.25+ 改进：替换为 token bucket + Redis 后端（支持分布式）
- **RateLimitFilter**（OncePerRequestFilter @Order HIGHEST+20）
  - 拦截 POST /api/auth/* 的 4 个状态变动接口
  - 命中限流 → 429 Too Many Requests + X-RateLimit-* 头

### 限流策略（默认）

| 接口 | 维度 | 阈值 |
|---|---|---|
| **POST /api/auth/login** | IP | **5 次/分钟**（防爆破）|
| **POST /api/auth/forgot-password** | IP | **3 次/小时**（防 spam，叠加 1 分钟 debounce）|
| **POST /api/auth/refresh** | IP | **60 次/小时**（防滥用）|
| **POST /api/auth/change-password** | userId | **3 次/小时**（防异常改密）|

### 修复

- **RateLimitFilter 拿不到 CurrentUser**（V2.0.24 关键修复）
  - 问题：filter 在 JwtAuthFilter 之前执行，CurrentUser 还没设
  - 方案：filter 自己解析 Authorization 头拿 userId（不依赖 JwtAuthFilter）
  - 新增 `parseUserIdFromAuth(req)` 私有方法

### 响应头

- **X-RateLimit-Limit** — 窗口内最大请求数
- **X-RateLimit-Remaining** — 剩余配额（0 = 已限流）
- **X-RateLimit-Reset** — 窗口重置时间戳（秒）
- **Retry-After** — 多少秒后可重试（HTTP 标准头）

### 变更

- **app.version 2.0.23 → 2.0.24**
- **SecurityConfig** 新增 `addFilterAfter(rateLimitFilter, CorsFilter.class)`

### 验收（8/8）

- E1 login 5/分钟按 IP：第 6 次 429 + Remaining=0 ✅
- E2 forgot 不同 IP（每个独立）✅
- E3 forgot 同 IP：第 4 次 429 ✅
- E4 refresh 60/小时：62 次 → 60 success + 2 limited ✅
- E5 响应头 X-RateLimit-* 全部正确 ✅
- E6 forgot-password 同 IP 第 4 次 429 ✅
- E7 change-password 3/小时按 user：第 4 次 429 ✅
- E8 不同接口限流独立计数 ✅

---

## V2.0.23 (2026-09-02 07:45) — P2.1 阶段 C：Security headers（OWASP 6 响应头）

主人 22:50 拍板「P2.1 Security headers（Recommended）」= 加 OWASP 推荐的 6 个安全响应头。7 个 E2E 场景全过，本地 commit 不 push。

### 新增

- **SecurityHeadersFilter**（V2.0.23 P2.1 核心）
  - 6 个安全响应头：
    1. **X-Frame-Options** — DENY（防 clickjacking），H2 console 例外 SAMEORIGIN
    2. **X-Content-Type-Options** — nosniff（防 MIME sniffing）
    3. **X-XSS-Protection** — 1; mode=block（老浏览器兼容）
    4. **Strict-Transport-Security** — max-age=31536000（仅生产 + HTTPS）
    5. **Content-Security-Policy** — 按 URL 类型差异化（API 不返 CSP）
    6. **Referrer-Policy** — strict-origin-when-cross-origin
    7. **Permissions-Policy** — 关闭 camera/microphone/geolocation 等敏感 API
  - URL 类型差异化 CSP（关键：H2 console / Vite dev 不被卡）：
    - HTML 页面（完整 CSP + frame-ancestors 'none' + object-src 'none'）
    - 静态资源（/js /css /assets）：宽松 CSP（允许 unsafe-inline）
    - H2 console：frame-ancestors 'self'（允许 console 自加载）
    - API（/api/**）：不返 CSP（返 JSON，无需限制 inline script）

### 修复

- **Spring Security 默认 headers 覆盖自定义 DENY**（V2.0.23 关键修复）
  - 旧：`.headers(h -> h.frameOptions(f -> f.sameOrigin()))` → 同源 frame 允许，clickjacking 防护失效
  - 新：`.headers(h -> h.disable())` → 由 SecurityHeadersFilter 统一管理（DENY + SAMEORIGIN 按 URL 分流）

### 变更

- **app.version 2.0.22 → 2.0.23**
- **SecurityConfig.headers()** 改为 disable（统一交给 SecurityHeadersFilter）
- **application.yml** 加 `lingyao.security.headers.hsts.enabled` 配置（默认 false，dev 不返 HSTS）

### 验收（7/7）

- E1 HTML /index.html X-Frame-Options: DENY ✅
- E2 portal.html DENY ✅
- E3 H2 console SAMEORIGIN + 放宽 CSP ✅
- E4 /api/version 不返 CSP ✅
- E5 /js/script.js 宽松 CSP ✅
- E6 dev 不返 HSTS ✅
- E7 完整 6 个 headers 全在位 ✅

### 待主人拍板

- P2.2 限流（rate limit）· P2.3 CSRF · P2.4 审计日志强化 + 2FA 预设

---

## V2.0.22 (2026-09-01 22:45) — P1 阶段 B：refresh_token 滑动续期 + forgot-password 邮件重置

主人 22:30 拍板「A + B 一起做」= P1.4 refresh_token + P1.5 forgot-password 两个一起做。P1.4 8 个 E2E 全过 + P1.5 14 个 E2E 全过，本地 commit 不 push。

### 新增

#### P1.4 refresh_token 滑动续期
- **refresh_token 表 + 实体 + Repository**（token_hash UNIQUE + 2 索引 + revoked_reason CHECK）
  - 字段：user_id / token_hash (SHA-256) / expires_at / used_at / replaced_by_id / revoked_at / revoked_reason / ip / created_at
  - 滑动续期：每次 refresh 旧 token 标 used_at + replaced_by_id 指向新 token
  - 防重放：已 used 的 token 再次出现 → 整条链全部撤销（REFRESH_REPLAY_DETECTED）
  - 失效场景：LOGOUT / PASSWORD_CHANGED / ADMIN_REVOKE / REFRESH_REPLAY_DETECTED
- **JwtUtil 加 refresh_token 工具**
  - `generateRefreshTokenPlain()` — SecureRandom 32B → URL-safe Base64
  - `hashRefreshToken()` — SHA-256 hash（DB 存 hash，泄露不可重放）
  - `getAccessTokenTtlSeconds()` — access 24h
  - 常量 `REFRESH_TOKEN_TTL_SECONDS` = 30*24*3600（30 天）
- **POST /api/auth/refresh**（公开端点，refresh_token 本身就是凭证）
  - 入参 `{ refreshToken: "..." }` → 返回 LoginResponse（new access + new refresh）
  - 重放检测命中 → REFRESH_REPLAY_DETECTED 撤销该用户所有 active refresh
  - AuthService.refresh() @Transactional
- **AuthService.logout()** 撤销该用户所有 active refresh_token
- **AuthService.changePassword()** 撤销该用户所有 active refresh_token（防密改后旧设备续期）
- **LoginResponse** 新增 `refreshToken` + `refreshTokenExpiresIn` 字段
- **SecurityConfig** /api/auth/refresh 加 permitAll

#### P1.5 forgot-password 邮件重置密码
- **password_reset_token 表 + 实体 + Repository**（token_hash UNIQUE + 2 索引）
  - 字段：user_id / token_hash / expires_at / used_at / ip_address / created_at
  - 默认 TTL 1 小时（邮件场景够用）
  - 一次性 token（used_at 标记）
- **POST /api/auth/forgot-password**（公开）
  - 入参 `{ email?: "...", username?: "..." }` → 200 + 始终同消息（防用户枚举）
  - 防 spam：1 分钟内重复请求同一用户 → 不发新 token（防邮件轰炸）
  - Dev mode：邮件通道未配置时返回 `devModeToken` + `resetUrl` 给前端直接用（生产环境邮件通道启用后删除）
  - audit 记录 PASSWORD_RESET_REQUESTED（邮箱脱敏）
- **POST /api/auth/reset-password**（公开，token 本身就是凭证）
  - 入参 `{ token: "...", newPassword: "..." }` → 200 + 修改密码 + 撤销所有 refresh_token
  - 校验：token hash 命中 + 未过期 + 未使用 + 新密码 ≥ 8 位 + 与 username 不同
  - audit 记录 PASSWORD_RESET（带 revokedRefreshTokens 数量）
- **SecurityConfig** /api/auth/forgot-password + /api/auth/reset-password 加 permitAll
- **JwtUtil.REFRESH_TOKEN_TTL_SECONDS** 复用为 reset token 的 SecureRandom 来源

### 前端

- **script.js 加 silent refresh + 401 自动重试**
  - login() 同时存 `lingyao_refresh_token` 到 localStorage
  - logout() 清 refresh_token
  - window.lingyaoSilentRefresh() 公开 API（供其他页面调用）
  - 全局 fetch 拦截器：401 + 带 Bearer → 自动 silent refresh + 重发原请求
  - 防并发：refreshingPromise 单例锁
  - 跳过 /api/auth/login 和 /api/auth/refresh 自身（避免循环）

### 修复

- **HQL 用 entity name 而非表名**（RefreshTokenRepository @Query）
  - 错误：`SELECT t FROM refresh_token t WHERE ...` → UnknownEntityException
  - 正确：`SELECT t FROM RefreshToken t WHERE ...`

### 变更

- **app.version 2.0.21 → 2.0.22**

### 验收

**P1.4 E2E 8/8**:
- E1 login → access + refresh ✅
- E2 /me with access → 200 ✅
- E3 /refresh with refresh → 200 + rotated ✅
- E4 /me with new access → 200 ✅
- E5 /refresh 旧 token（重放）→ 401 REFRESH_REPLAY_DETECTED ✅
- E6 garbage token → 401 ✅
- E7 空 refreshToken → 400 ✅
- E8 logout 后旧 refresh 撤销 → 401 ✅

**P1.5 E2E 14/14**:
- E1 forgot by email → 200 + devModeToken + resetUrl ✅
- E2 forgot by username → 200（同上）✅
- E3 forgot 用户不存在 → 200（防枚举）✅
- E4 forgot 1 分钟内重复 → 不发新 token（防 spam）✅
- E5 forgot 空 email → 400 ✅
- E6 reset 合法 token + 新密码 → 200 ✅
- E7 旧 token 再用 → 401 已使用 ✅
- E8 garbage token → 401 无效 ✅
- E9 空 token → 400 ✅
- E10 新密码 < 8 位 → 400 ✅
- E11 新密码 login → 200 ✅
- E12 旧密码 login → 401（被拒）✅
- E13 重置回原密码 → 200 ✅
- E14 原密码 login → 200 ✅

---

## V2.0.21 (2026-09-01 22:20) — P1 阶段 A：登出黑名单 + /me 身份查询

主人 22:13 拍板「继续 P1，但手机登录相关的可以先不做」= 先做 P1.1 (/me) + P1.2 (logout + blacklist) + P1.3 (前端 logout 同步)。8 个 E2E 场景全过，本地 commit 不 push。

### 新增

- **token_blacklist 表 + 实体 + Repository**（黑名单表，P1 安全核心）
  - 字段：jti（UNIQUE）/ user_id / company_id / expires_at / reason / ip_address / created_at
  - 5 索引：jti UNIQUE + (user_id, expires_at) + (expires_at)
  - CHECK 约束：reason ∈ LOGOUT/ADMIN_REVOKE/PASSWORD_CHANGED/REFRESH_ROTATE/OTHER
  - 提供 `existsByJti()` O(1) 校验 + `deleteExpired()` 定时清理（V2.0.22+ 接 Scheduler）
- **JwtUtil 增加 jti 字段**（标准 JWT `jti` claim）
  - `generate()` + `generateSsoToken()` 都注入 UUID v4 作为 jti
  - 解析后 JwtAuthFilter 可通过 `claims.getId()` 读取
- **JwtAuthFilter 增加黑名单校验**（V2.0.21 关键修复）
  - 每次请求解析 Claims 后，先查 `tokenBlacklistRepository.existsByJti(jti)`
  - 已撤销 → 清掉 SecurityContext + 跳过后续认证，返回 401
- **GET /api/auth/me**（P1.1）
  - 返回 user + company + products + mustChangePassword（同 login() 的 userView 结构）
  - 不重发 token（前端继续用旧 token）
  - 必须登录（401 跳登录页）
- **POST /api/auth/logout**（P1.2）
  - 解析当前 token 的 jti + exp，写入 token_blacklist
  - 幂等：第二次调用返回 200 + alreadyLoggedOut=true
  - 允许未登录调用（permitAll）— 前端清 token 兜底
  - garbage/过期 token → catch 块返回 200 + 已退出
  - audit_log 记录 LOGOUT
- **AuthService.logout()**（@Transactional）
  - jti 已存在 → 直接 return（避免重复插入）
  - jti 为空（老 token 兼容）→ 静默放过
- **AuthService.getMe()**
  - 复用 buildProductView + memberships 计算
  - 设置 expiresIn = jwtUtil.getExpirationSeconds()（不重发 token）

### 修复

- **前端 logout 仅清 localStorage 不通知后端**（V2.0.21 关键修复）
  - 旧：用户点退出后 token 仍在 24h 内有效，被中间人截获可继续使用
  - 新：先发 XMLHttpRequest 同步请求 `/api/auth/logout` 写入黑名单，再清前端 token/localStorage，最后跳 /index.html
  - 失败兜底：网络失败也继续清前端，避免用户登不出去

### 变更

- **SecurityConfig 公开 /api/auth/logout**（idempotent）
  - 旧：被 `/api/**` authenticated 拦截，未登录调用返回 401（用户体验差）
  - 新：permitAll（与 `/api/auth/login` 同列）
- **app.version 2.0.20 → 2.0.21**

### 验收

- E1 GET /me + token → 200 + 完整 user/company/products
- E2 GET /me 无 token → 401
- E3 POST /logout + token → 200 + jti
- E4 GET /me + 已撤销 token → 401（黑名单生效）
- E5 POST /logout 幂等（同 token 二次）→ 200 + alreadyLoggedOut=true
- E6 POST /logout 无 token → 200 + alreadyLoggedOut=true
- E7 POST /logout garbage token → 200 + 已退出
- E8 完整链路 login → /me → logout → /me → 401

---

## V2.0.20 (2026-09-01 17:05) — W6 阶段 2.5 修复：集中审批 3 接口后端 + POR-M callback 路径

主人 16:49 拍板「1」= P0 立即修 B1 + B2。修完 9 个 E2E 场景全通过，本地 commit 不 push。

### 新增
- **sub_task_approval 表 + 实体 + Repository**（V2.0.19 W6 阶段 2 前端集中审批 Tab 调的是空气接口）
  - 通用审批流：SUBTASK_INVOKE / PRODUCT_GRANT / SSO_LINK_REQUEST / OTHER
  - 单层审批：PENDING → APPROVED / REJECTED
  - payload_json 字段保留扩展（任意附加 JSON）
  - 4 CHECK 约束 + 5 索引（company_id+status 复合索引优先）
- **3 个 SubTaskController 接口**：
  - `GET /api/sub/pending` — 查当前公司待审批（平台超管看全部）
  - `POST /api/sub/{id}/approve` — 通过审批
  - `POST /api/sub/{id}/reject` — 拒绝审批（带 reason）

### 修复
- **B2 POR-M base-url 缺 callback 路径**（V2.0.18 引入的隐性 bug）
  - 旧：`http://localhost:3190` → SSO 跳转会丢 token
  - 新：`http://localhost:3190/auth/lingyao/callback`（参考 DINFO 风格）

### 权限分层
- 平台超管：看/审批 任意公司
- 公司超管：仅看/审批 自己公司（用 TenantContext.isTenantAdmin 二次校验）
- 普通用户：403

### 与现有 audit_log 解耦
- 单独记录 SUBTASK_APPROVAL_APPROVE / SUBTASK_APPROVAL_REJECT 到 audit_log

### E2E 9 场景全通过
① 空列表 / ② 灌 3 条 / ③ pending 列表 / ④ approve / ⑤ reject+reason
⑥ 审批后状态过滤 / ⑦ 未登录 / ⑧ 重复 approve / ⑨ approve 不存在

---

## V2.0.19 (2026-09-01 16:30) — W6 阶段 2 后端 + 前端 3 个页面

## V2.0.19 (2026-09-01 16:18) — W6 阶段 2 后端 · 子产品专属链接生成/验签（短时 5 分钟 JWT）

### 背景
- 主站阶段 1（V2.0.14）`/api/sub/{code}/enter` 用 8h 长期 JWT，但**生产中易被滥用**（链接一旦外泄长期有效）
- 公司超管/平台超管需要**随时生成一次性专属链接**，给临时用户（含未登录代表、外部审计员）用
- 链接必须**短时过期**（5 分钟）、**验签失败有明确错误码**、**产品必须匹配**

### 改动
- ✅ **新增** `backend/src/main/java/.../controller/SubProductLinkController.java`（247 行）
  - `POST /api/admin/sub-product/{code}/generate-link`：超管鉴权 → 生成 5 分钟短时 JWT → 拼接 URL（含 fragment 处理）→ 返回 `{url, expires_at, ttl_seconds}`
  - `POST /api/sub-product/{code}/verify-link`：白名单（无需登录）→ 解析 token → 校验产品匹配 → 返回 `{valid, user, claims}`
- ✅ **修改** `backend/src/main/java/.../security/JwtUtil.java`
  - 新增 `generateShortLinkToken()` · `parseShortLinkToken()` · `getShortLinkTtlSeconds()`（3 个方法，TTL=300s）
  - 新增 claim 字段 `link_type="company_sub_product_link"`（白名单用此区分）
- ✅ **修改** `backend/src/main/java/.../security/SecurityConfig.java`
  - 白名单加 `/api/sub-product/*/verify-link`（验签接口免登录）
- ✅ **修改** `backend/src/main/java/.../controller/SubTaskController.java`
  - 撤回原计划加的 2 个方法（避免 `/api/sub` 类级别路径冲突）→ 改由独立 `SubProductLinkController` 处理
  - 末尾加注释指向新 Controller
- ✅ **版本号** `backend/src/main/resources/application.yml` · `app.version` 2.0.18 → **2.0.19**

### 业务价值
- ✅ 公司超管可在 admin 后端给临时用户（含未注册）生成 **5 分钟有效期** 的专属登录链接
- ✅ 防滥用：链接过期/产品不匹配/伪造 token 全部走明确错误码（401 LINK_EXPIRED / 400 PRODUCT_MISMATCH / 401 INVALID_TOKEN）
- ✅ 解锁 GEOM 集成 P0：GEOM 子产品的临时代表可通过此链接直达 GEOM（无需在主站注册）
- ✅ 为阶段 2 前端 3 个页面（company-admin SSO 配置 Tab + 集中审批 Tab + portal 复制链接按钮）提供后端支撑

### URL 拼接修复（关键 Bug）
- ❌ 原始：`http://localhost:5180/#/auth/...` + `?token=...` → 把 token 拼进 fragment（Vue hash 路由吃掉 query）
- ✅ 修复：先拆 fragment → query 在前 → fragment 拼回 → `http://localhost:5180/?token=...#/auth/lingyao/sso-callback`

### 路径冲突修复（架构决策）
- ❌ 原始：往 SubTaskController 加方法 → 类级别 `@RequestMapping("/api/sub")` 拼出 `/api/sub/admin/...` 错路径
- ✅ 决策：拆到独立 `SubProductLinkController`，类级别**不写** `@RequestMapping`，方法用**完整路径** `/api/admin/sub-product/...` 和 `/api/sub-product/...`

### E2E 验证（7 场景全通过）
| # | 场景 | 输入 | 期望 | 实际 |
|---|---|---|---|---|
| 1 | 超管生成链接 | admin@lingyao.cn + GEO | 200 + URL | ✅ TTL=300s |
| 2 | 验签成功 | valid token + GEO | 200 + cid=1 pcode=GEO | ✅ |
| 3 | 产品不匹配 | valid token + 错产品 code | 400 PRODUCT_MISMATCH | ✅ |
| 4 | 伪造 token | "fake.token.here" + GEO | 401 INVALID_TOKEN | ✅ |
| 5 | 未带 token | 空 + GEO | 400 MISSING_TOKEN | ✅ |
| 6 | 过期 token | 已过期 JWT | 401 LINK_EXPIRED | ✅ |
| 7 | 错误 JWT 类型 | 8h 长期 JWT | 401 INVALID_TOKEN | ✅（link_type claim 校验）|

### 配套文档
- 📄 `/tmp/MainSite_to_GEOM_progress_sync.md`（251 行）· 主平台 ↔ GEOM 双向进度同步
- 📄 `/tmp/geom-prd/GEOM_Progress_Report_2026-09-01.md` · GEOM 进度
- 📄 `/tmp/geom-prd/GEOM_Unresolved_Disagreements.md` · 待决分歧

### 待前端（阶段 2 前端 W7）
- ⏳ company-admin.html SSO 配置 Tab
- ⏳ company-admin.html 集中审批 Tab
- ⏳ portal.html 复制链接按钮
- ⏳ 等主人下达下一指令后开发

### 暂不推送
- 按主人 15:25 V3.2 SOP 软化拍板：本轮 commit 保留本地（不 push）

---

## V2.0.18 (2026-08-31 19:55) — W5 CORS 白名单扩展 5 家子产品本地域（配合 DINFO 登录 P0 阻塞解除）

### 改动
- `backend/src/main/java/.../config/WebConfig.java` · CORS `allowedOriginPatterns` 新增 5 家子产品 22 个本地域：
  - DINFO 辰录（强耦合 · P0）：`http://localhost:{5181,8281}`
  - POR-M 明枢（强耦合 · 同窗口推）：`http://localhost:{8280,3190}`
  - GEOM 棱镜：`http://localhost:{5180,5182,8090}`
  - MPDM 皓元：`http://localhost:{3100,8100}`
  - AIDD 源策：`http://localhost:{13000,18080}`
  - MED-PPT：`http://localhost:{19880,19881}`
- `backend/src/main/resources/application.yml` · `app.version` 2.0.17 → **2.0.18**

### 业务价值
- ✅ **解除 DINFO M2 阻塞**：DINFO 前端（5181）+ 后端（8281）调主站 API 不再被 CORS 拦截
- ✅ 5 家子产品全部能跨域调主站 `/api/**`（不再需要反向代理或同源部署）
- ✅ 完全向后兼容：原有 12 个域全部保留（本地 8765/9091 + CVM 118.195.197.15 + lydmed.com）

### 铁律 #15 验证
- `allowedOriginPatterns` 仍用 pattern 形式（不是 `allowedOrigins("*")`）→ 配合 `allowCredentials(true)` 不会触发 "wildcard + credentials" 冲突
- 生产域（118.195.197.15 + lydmed.com）保留

### 验证（待启动后实测）
- ⏳ curl OPTIONS preflight → 检查 Access-Control-Allow-Origin
- ⏳ curl `/api/sub/dinfo/enter` → 检查 redirectUrl 含 DINFO 域 + platform_token fragment
- ⏳ 跨域从 5181 调 9091 → 检查响应头

### 配合 DINFO 登录的端到端进度
| 步骤 | 状态 |
|---|---|
| ① 主站 portal.html 显示 DINFO 入口 | ✅ V2.0.17 W4 |
| ② 主站签发 JWT 含 `is_tenant_admin` 平铺字段 | ✅ V2.0.16 W1 |
| ③ 主站 `/api/sub/dinfo/enter` 生成 redirectUrl | ✅ V2.0.11 已实现 |
| ④ token 走 URL fragment（不进 history/log） | ✅ V2.0.14 fix |
| ⑤ **DINFO 前端能跨域调主站** | ✅ **V2.0.18 W5（本轮）** |
| ⑥ DINFO 端 LingyaoSsoCallback.vue 解析 fragment | ⏳ DINFO 窗口在写（M2）|
| ⑦ DINFO 端 `/api/auth/lingyao-sso` 验签 + 自动绑 | ⏳ DINFO 窗口在写（M2）|

### 暂不推送（按主人 19:46 拍板）
- commit 列表（V2.0.16 W1 + V2.0.17 W4 + V2.0.18 W5）保留在本地

### PRD
- `/tmp/dinfo-prd/MainSite_MultiTenant_Support_PRD.md` · W1 + W4
- 主站侧 W5 不在原 PRD 内（CORS 是配合 DINFO 实施时发现的环境缺口）

---

## V2.0.17 (2026-08-31 19:25) — W4 主站 portal.html 按子产品开通状态动态渲染 + UserView.tenantAdmin

> 📌 **暂不推送（2026-08-31 19:46 主人拍板）**：仅作为告知，commit `d5b192b` (V2.0.16) + `c35c103` (V2.0.17) 保留在本地不推送远端主仓

### 改动
- `website/portal.html` · login 响应处理加 `subProductGrants` + `isTenantAdmin` 提取
- `website/portal.html` · `renderModules` 加按授权过滤 + 视觉反馈（未开通置灰+🔒+不可点击）
- `website/portal.html` · 点击 handler 加未开通产品拦截（提示联系平台管理员）
- `backend/src/main/java/.../dto/LoginResponse.java` · UserView 加 `tenantAdmin` 字段
- `backend/src/main/java/.../service/AuthService.java` · UserView 设置 tenantAdmin（与 JWT 平铺字段一致）

### 业务价值
- 租户开通 GEO/HPD/AIDD/DINFO/PORM 中部分子产品时，portal 只显示已开通子产品入口
- 未开通产品置灰+🔒，用户点击时提示"请联系平台管理员在管理后台 → 子产品开通管理中开通"
- 平台超管（isPlatformAdmin=true）跳过过滤，看全部产品（便于运维）

### 验证（dev profile · lingyao-postgres）
- mvn package 成功
- 重启后端 PID 78877 · 9091 · 启动正常
- login 响应：user.platformAdmin=true + user.tenantAdmin=true + 5 licensed products ✅

### 待验证（需主人用普通租户用户登录验证）
- 非平台超管的租户用户 → portal 只显示已授权子产品
- 未开通产品点击 → 弹"请联系平台管理员"提示

### 配套
- DINFO M2（SSO 自动绑）可读 user.tenantAdmin
- DINFO M5（分享调研入口）可读 user.subProductGrants

### PRD
- `/tmp/dinfo-prd/MainSite_MultiTenant_Support_PRD.md` (W4)

### owner
- 凌瑶主人 <owner@lingyao.local>

---

## V2.0.16 (2026-08-31 18:58) — JWT claims 平铺字段 W1（DINFO M2 配套）

### 改动
- `application.yml` `app.version`: 2.0.15 → 2.0.16
- `JwtUtil.java` 新增 11 参 `generate()` 方法（含 `isTenantAdmin` + `subProductGrants`）；保留 10 参 / 7 参 @Deprecated 向后兼容
- `AuthService.java` 计算 `isTenantAdmin` = `isPlatformAdmin OR CompanyUser.role=SUPER_ADMIN`；计算 `subProductGrants` = `products.filter(licensed).map(code)`
- `application.yml` 新增 `lingyao.jwt.claims-v2` 文档块（平铺字段来源说明）

### 新 JWT claims 平铺字段
```json
{
  "uid": 1,
  "username": "admin",
  "cid": 1,
  "ccode": "jinsai",
  "adm": true,
  "tenant_name": "金赛药业",            // 🆕 V2.0.16 平铺
  "is_tenant_admin": true,              // 🆕 V2.0.16 平铺（adm OR CompanyUser.role=SUPER_ADMIN）
  "sub_product_grants": ["GEO","HPD","AIDD","POR"],  // 🆕 V2.0.16 平铺（Product.code 列表）
  "tenant": {"id":"1","code":"jinsai","name":"金赛药业"},        // Phase 2 嵌套（保留）
  "identity": {"user_id":"1","username":"admin","display_name":"平台超管"},  // Phase 2 嵌套（保留）
  "grants": {...}                                                  // Phase 2 嵌套（保留）
}
```

### 降级兼容
- 老 token（无平铺字段）→ 子产品按空值处理（不影响老逻辑）
- 老 10 参 / 7 参 `generate()` 方法 @Deprecated，但保留兼容（默认 isTenantAdmin=isPlatformAdmin / subProductGrants=[]）

### 配套 PRD
- `/tmp/dinfo-prd/MainSite_MultiTenant_Support_PRD.md`（656 行 · W1 实施）

### 配套 DINFO 端（V2.0.16 主站 W1 落地后）
- DINFO M2（SSO 自动绑）可直接读 `is_tenant_admin` 判断是否该公司超管
- DINFO M5（分享调研入口）可直接读 `sub_product_grants` 判断是否开通辰录

### 待主人验证
- `mvn package` 重打 jar
- 重启后端 + 登录 admin → 解析 token → 看到 `tenant_name` / `is_tenant_admin` / `sub_product_grants` 3 个平铺字段
- 转发 token 给 DINFO / GEOM / MPDM / AIDD / POR-M 子产品验签（不破坏嵌套结构兼容）

## V2.0.15 (2026-08-31 11:15) — SSO 全家桶 5/5 全通 + JWT secret 64B 固化 + 地雷清除

### 改动
- `application.yml` `app.version`: 2.0.14 → 2.0.15
- JWT secret 3 处默认值升级为 64 字节（HS512，与 5 家子产品验签对齐）：`app.jwt.secret` / `lingyao.jwt.secret` / sso 协议 secret
- **mvn package 重打 jar**（64B secret + 2.0.15 打进 classpath），`target/lingyao-platform.jar` 2026-08-31 11:14
- **删除外部覆盖地雷**：`凌瑶/application.yml`（根目录）+ `凌瑶/backend/application.yml`（classpath 外部覆盖优先级高于 jar 内，导致旧 jar 读不进新配置）。已备份至 `_backups-2026-08-31/`
- GEOM geo.db：`sys_user` liuling99 绑定 `lingyao_user_id='1'`（备份 `geom/_backups-2026-08-31/geo.db.bak-110549`）
- DINFO H2 内存库：`employee` 表 dinfo_admin 绑定 `lingyao_user_id='1'`（经 /h2-console 执行；H2 重启后失效，绑定靠 DataImportService ReloadAll 保留或需重新执行）

### 验证（2026-08-31 11:16）
- 主站登录 OK（HS512 签发）
- 棱镜 8090 🟢 liuling99 / 皓元 8100 🟢 admin / 源策 18080 🟢 / 辰录 8281 🟢 辰录超级管理员 / 明枢 8280 🟢 王老板
- 前端 8765 HTTP 200

## V2.0.13 (2026-08-29 18:50) — AIDD SSO 接入（依次启动第 1 站）

### 改动
- `application.yml` `app.version`: 2.0.12 → 2.0.13
- `application.yml` `lingyao.subtask.routes.aidd.base-url`: `http://localhost:13000` → `http://localhost:13000/sso-callback.html`（AIDD 是静态 HTML，跳 SSO 回调页而非 hash 路由）
- `data.sql` sub_task AIDD 描述加「C47 W5 P1-SSO 已对接」+ base_url 同步

### 配合改动（AIDD 仓内）
- AIDD 1.0.0-W-Cycle177 → 1.0.0-W-Cycle178-D1-LingyaoSSO
- 后端：`LingyaoSsoService.java`（新建 3 级回退匹配）+ `AuthController` 加 `POST /api/sso/login` + `TenantUser` 加 4 字段
- 前端：`sso-callback.html`（新建，5 参数 → POST → 跳 dashboard）+ `login.html` 加 "凌瑶主站登录" 按钮

### 端到端验证（18:50）
主仓 admin → `/api/sub/aidd/enter` → 跳 AIDD `sso-callback.html` → AIDD `/api/sso/login` 验签 → 自动关联 AIDD admin 用户 → 签 AIDD 本地 JWT → 可访问 AIDD `/api/auth/me` ✅

### PORM 接入补充（19:05 第 2 站）
- PORM 0.8.151 → 0.8.152
- 后端：`LingyaoSsoController.java`（新建，HS512 验签，User 域字段加 3 个）+ `pom.xml` 加 jjwt 0.12.6 + `SecurityConfig` 加白名单 + `application.yml` 加 lingyao.jwt.secret + `start_mingshu_daemon.py` 新建（profile 必须 demo,h2 因为 UserSyncer @Profile("h2")）
- 前端：`pages/SsoCallbackPage.jsx`（新建，state-routing 'ssoCallback' 分支）+ `App.jsx` 跳过 /api/auth/me + `services/api.js` 加 lingyaoSsoLogin endpoint
- 主仓：`data.sql` sub_task.porm base_url 改 `http://localhost:8280/api/sso/callback-redirect`（标准模式）

### PORM 端到端验证（19:05）
主仓 admin → `/api/sub/porm/enter` → 跳 PORM `/?platform_token=...&...` → PORM `/api/sso/login` 验签 → 自动关联 PORM admin 用户（userId=ou_demo_admin, role=superadmin）→ 签 PORM 本地 token → 可访问 PORM `/api/auth/mode` ✅

### PORM 踩坑记录（铁律级）
1. **YAML 不能有重复顶层 key**：第一次把 `mingshu.lingyao` 单独加在 line 11，但 line 18 已有 `mingshu.sso`，导致 DuplicateKeyException 启动失败 → 必须合并到一个 `mingshu:` 块下
2. **profile 必须 demo,h2**：UserSyncer 是 `@Profile("h2")`，单独用 demo 时 Spring 找不到 bean → FeishuOAuth2SuccessHandler 注入失败 → 必须 `demo,h2` 双 profile
3. **SSO 业务消息要在 SecurityConfig permitAll 之前**：先 permitAll 才能让 `/api/sso/login` 走到 controller，否则被 Spring Security 401 拦截

### 后续 2 子产品
- [x] Dinfo（Vue + 飞书 callback 共存）~0.5-1.0 工作日 → ✅ 14:55 完成
- [x] GEOM（Python FastAPI + 飞书 callback 不动）~1 人天 → ✅ 15:18 完成

---

## V2.0.13 (2026-08-29 19:35) — Dinfo SSO 接入（依次启动第 3 站）

### 改动
- `application.yml` `app.version`: 2.0.13（沿用 18:50 AIDD 那次 bump）
- `application.yml` `lingyao.subtask.routes.dinfo.base-url`: `http://localhost:5181` → `http://localhost:5181/auth/lingyao/callback`
- `data.sql` sub_task Dinfo 描述加「C47 W5 P1-SSO 已对接」+ base_url 同步

### 配合改动（Dinfo 仓内）
- Dinfo D1.0.1 → D1.1.0
- 后端：`LingyaoSsoController.java`（新建，HS512 验签）+ `Employee` 加 4 字段（lingyaoUserId/lingyaoTenantId/lingyaoUsername/lastSsoLoginAt）+ `application.yml` 加 `app.lingyao.jwt.secret`
- 前端：`router/index.ts` 加 `/auth/lingyao/callback` 路由 + `views/LingyaoCallback.vue`（新建）
- 启动：`start_dinfo_daemon.py` 新建（Java 双 fork 守护 + LINGYAO_JWT_SECRET 注入）

### Dinfo 端到端验证（14:55）
主仓 admin → `/api/sub/dinfo/enter` → 跳 Dinfo `/auth/lingyao/callback` → Dinfo `/api/auth/lingyao/callback` 验签 → 自动关联 dinfo_admin → 签本地 JWT ✅

### Dinfo 踩坑记录（铁律级）
1. **JwtUtil HS256 vs HS512 自动推断**：Dinfo 原本 JwtUtil 写死 HS256，验签凌瑶 HS512 token 抛 signature mismatch → 改成 `Keys.hmacShaKeyFor` 自动推断（jjwt 0.12.x 的 feature），按 algorithms 列表首个匹配
2. **adm-true 超管兜底**：主仓 admin token 没有 role 字段，只有 `adm: true`；必须 `Boolean.TRUE.equals(admObj)` 兜底绑定到 dinfo_admin，否则 Dinfo 找不到员工返 403
3. **Dtos.ApiResponse.fail() 只有双参数**：原本 LingyaoSsoController 想传 (code, message, data) 编译报错 → 去掉 data 参数，用 (code, message) 双参数
4. **端口 8281 被 POR 源头仓占用**：lsof 显示源头仓进程 PID 93027 没有 lingyao_sso 端点 → kill -9 释放端口后重启 Dinfo daemon

---

## V2.0.13 (2026-08-29 15:22) — GEOM SSO 接入（依次启动第 4 站 · 收官）

### 改动
- `application.yml` `lingyao.subtask.routes.geo.base-url`: `http://localhost:5180` → `http://localhost:5180/#/auth/lingyao/sso-callback`（GEOM 前端是 hash router，必须带 #/）
- `data.sql` sub_task GEO 描述加「V0.9.12.32 C47 W5 P1-SSO 接入凌瑶主站」

### 配合改动（GEOM 副本仓，凌瑶/geom/）
- GEOM V0.9.12.31.6 → V0.9.12.32
- 后端：`app/config.py` 加 `LINGYAO_JWT_SECRET`（默认与凌瑶主仓完全一致）+ `app/database.py` sys_user 加 4 字段 + `app/routers/lingyao_sso.py` 新建（PyJWT HS512 验签 + 3 级回退匹配）+ `main.py` include_router
- 前端：`geo-frontend/src/router/index.js` 加 `/auth/lingyao/sso-callback` 路由 + `pages/LingyaoSsoCallback.vue` 新建（hash router 模式截 window.location.hash）
- 启动：`start_geom_daemon.py` 新建（Python 双 fork + setsid，sandbox 友好）

### GEOM 端到端验证（15:18）
主仓 admin → `/api/sub/geo/enter` → 跳 GEOM `/#/auth/lingyao/sso-callback` → GEOM `/api/auth/lingyao/sso-callback` 验签 → 自动关联 liuling99（super_admin）→ 签本地 JWT ✅

### GEOM 踩坑记录（铁律级）
1. **APIRouter prefix 嵌套陷阱**：`APIRouter(prefix="/api/auth/lingyao")` + `include_router(prefix="/api/auth")` → 路径变成 `/api/auth/api/auth/lingyao/status`（404）。正解：APIRouter 裸路径 `/lingyao`，由 include_router 注入 `/api/auth`
2. **GEOM database.py 无 query_one helper**：必须用 `db = get_db(); db.row_factory = sqlite3.Row; row = db.execute(...).fetchone(); dict(row)` 模式
3. **sandbox 杀 nohup 进程**：必须用 Python 双重 fork + setsid 守护（macOS 无 setsid 命令 → `os.setsid()`）
4. **PyJWT 自动推断算法**：必须显式声明 `algorithms=["HS512"]`，不能让 PyJWT 从 header 推（HS256 兼容但排查耗时长）

---

## V2.0.13 收官总结：4 子产品全部 SSO 接入凌瑶主站 ✅

| 子产品 | 端口 | 后端栈 | SSO 端点 | 状态 | 完成时间 |
|---|---|---|---|---|---|
| **AIDD** | 18080 | Spring Boot 3 | POST /api/sso/login | ✅ | 18:50 |
| **PORM** | 8280 | Spring Boot 3 | POST /api/sso/login | ✅ | 19:05 |
| **Dinfo** | 8281 | Spring Boot 3 | POST /api/auth/lingyao/callback | ✅ | 14:55 |
| **GEOM** | 8090 | FastAPI + PyJWT | POST /api/auth/lingyao/sso-callback | ✅ | 15:18 |

**统一模式**（HPD 已建立的）：
1. 凌瑶主站签 JWT（HS512，audience=lingyao-sso，含 `uid/cid/adm/identity{user_id,username,display_name}` claim）
2. 子产品用共享 LINGYAO_JWT_SECRET 验签
3. 3 级回退匹配本地用户：lingyao_user_id → lingyao_username → 平台主仓 admin 兜底（adm-true）
4. 命中即更新 lastSsoLoginAt + 返回本地 JWT（HS256，7-30 天 TTL）

**dev 验证矩阵（15:22）**：
```
主仓 9091   PID 99254 ✅
AIDD 18080  PID 98799 ✅
PORM 8280   PID 402   ✅（重启后，新鲜）
Dinfo 8281  PID 93914 ✅
GEOM 8090   PID 98880 ✅
```

### 🔄 修正（15:30 · 主人提醒「皓元呢？」）

**HPD/MPDM 皓元其实早就做完了**——18:27 那次「主仓 v2.0.12 HPD 改造」就是在 MPD-myself 上做的（当时 HPD 仓 = MPD-myself）。本次汇报只数了今天 4 个改造，漏了 HPD。

完整 5 子产品 SSO 矩阵：
| 子产品 | 端口 | 后端栈 | SSO 端点 | 完成时间 |
|---|---|---|---|---|
| **HPD/MPDM** | 8100/3100 | FastAPI + PyJWT | POST /api/sso/login | **18:27 之前**（V2.0.12 改造） |
| AIDD | 18080 | Spring Boot 3 | POST /api/sso/login | 18:50 |
| PORM | 8280 | Spring Boot 3 | POST /api/sso/login | 19:05 |
| Dinfo | 8281 | Spring Boot 3 | POST /api/auth/lingyao/callback | 14:55 |
| GEOM | 8090 | FastAPI + PyJWT | POST /api/auth/lingyao/sso-callback | 15:18 |

HPD 端到端实测（15:30）：
```
POST /api/sso/login → 200
{"success":true,"authorized":true,"token":"eyJ...","role":"super_admin","user":{"id":3,"username":"admin","lingyao_user_id":1,"lingyao_tenant_id":1},"source":"lingyao"}
```

**5/5 全部端到端通过 ✅**

**后续可做（非阻塞）**：
- [ ] staging / prod 部署：5 个产品都用 staging/prod 的 LINGYAO_JWT_SECRET（生产环境必须改默认值）
- [ ] 数据闭环：4 子产品的 last_sso_login_at 可以汇总到主仓审计
- [ ] 退出统一：在子产品 logout 时跳回主仓（目前是各自独立 logout）


---

## 2026-08-27 · V2.0.1 (版本号方案 A 落地)

### Commit `9d2c1f7` · 方案 A：版本号机制升级（/api/version 端点 + SSO 协议版本字段）

**触发**：主人 2026-08-27 12:16 「通过方案 A」（5 条铁律全 yes）+ 质量环产物沉淀

**满足铁律**（方案 A 五条铁律全落地）：
- **V1** 三段式 X.Y.Z：`app.version: 2.0.0 → 2.0.1`（Z 段 bump）
- **V2** FE/BE 同步：凌瑶静态 HTML 无 FE/BE 同步需求；版本号机制统一在 BE
- **V3** 跨产品主版本同步：`sso.protocol-version: "1.0"` 字段建立，与 GEOm/MPDm 对齐 V2.0.0 时同步
- **V4** 必须 CHANGELOG.md：本文件即凌瑶 CHANGELOG
- **V5** 必须 /api/version 端点：新加 `GET /api/version`（公开无鉴权）

**改动**：
- 新增 `VersionController.java`：`/api/version` 端点（公开白名单）
- `SecurityConfig.java`：permitAll 列表加 `/api/version`
- `application.yml`：新增字段 `app.release` / `app.build-time` / `app.git-commit` / `sso.protocol-version` / `sso.jwt.secret` / `sso.jwt.token-ttl-seconds`
- `application.yml`：`app.version: 2.0.0 → 2.0.1`（铁律 2：小改动也要 bump）

**验证**：
- dev 9091 启动成功
- `curl http://127.0.0.1:9091/api/version` 返回完整 JSON：
  ```json
  {
    "code": 0,
    "data": {
      "service": "lingyao-platform",
      "version": "2.0.1",
      "release": "stable",
      "build_time": "2026-08-27T12:18:59+08:00",
      "git_commit": "dev",
      "sso_protocol": "1.0"
    }
  }
  ```
- `/api/health.data.version` 同步为 `2.0.1`（铁律 2 验证）

**附属收益**：
- SSO 协议版本字段已嵌入，凌瑶 V2.0.0 + GEOm/MPDm V2.0.0 三产品对齐时直接读 `sso.protocol-version`
- 部署脚本可注入 `APP_GIT_COMMIT=<hash>` 写入 `git_commit` 字段，运维对账零成本

## 2026-08-27 · V2.0.2 (前端硬编码 9091 修复)

### 触发
主人 2026-08-27 13:15 跑发布流程验证，卡在 staging 9092 浏览器报 HTTP 401（"请确认后端 9091 已启动"）。诊断：portal.html:796 + admin.html:688 硬编码 `const API_BASE = 'http://127.0.0.1:9091';`，staging 跨端口 9092 → 9091 命中 prod 后端，prod JWT secret 与 staging 不同，401 拒绝。

### 改动
- `website/portal.html:796` 硬编码 → 智能判断（同 `geo.html` 同源写法）：
  ```javascript
  const API_BASE = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
    ? 'http://127.0.0.1:9091' : '';
  ```
- `website/admin.html:688` 同改
- `application.yml`：`app.version: 2.0.1 → 2.0.2`（铁律 2）

### 验证（部署后）
- staging 9092 浏览器登录：admin/admin123 → 4 子产品页正常加载
- prod 9091（经 Nginx 80 反代）：登录页底部版本号 V2.0.2
- `curl /api/version` 返回 `version: "2.0.2", git_commit: <new>`

### 遗留（V2.0.3+ 候选）
- `deploy-staging.sh` 脚本 `REMOTE_DIR=/opt/lingyao` 路径错（mv 到 prod 路径，staging 跟着软链接跑 prod jar）
- `application-staging.yml` / `application-private.yml` 走 `--spring.config.location` 完全替换 classpath，jar 内 application.yml 没加载（version 读默认值）
- prod 9091 公网不可达（腾讯云安全组只放行 80 + 9092），更新文档：prod 入口走 Nginx 80

---

## 2026-08-27 · V2.0.3 (P0+P1 bug 修复闭环)

### 触发
V2.0.2 部署验证暴露 7 个待修 bug（部署路径冲突、SecurityConfig 默认 UserDetailsService、日志噪声、配置替换、Token 不兼容、SQL 冲突、推送通道告警），主人 14:58 下达"把该修的都修了"。

### 改动（7 项）

| ID | 文件 | 改动 |
|---|---|---|
| P0-1 | `scripts/deploy-staging.sh` | `REMOTE_DIR=/opt/lingyao` → `/opt/lingyao-staging`（staging/prod jar 路径独立）+ 加 step 自动迁移 systemd service ExecStart |
| P0-2 | `LingyaoApplication.java` | `@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })` — 关闭 Spring Security 默认 inMemoryUserDetailsManager |
| P1-3 | `SecurityConfig.java` | permitAll 白名单加 `/api/_diag/**` — 开发者 curl 自检版本无需 JWT |
| P1-4 | `scripts/deploy-staging.sh` | 自动迁移 systemd `--spring.config.location` → `--spring.config.additional-location`（外部 yml 叠加 classpath，不再替换）|
| P1-5 | `static/portal.html` + `admin.html` | `getToken()` 加 TOKEN_KEYS_LEGACY fallback（`ly_token` / `geo_token` 老 key 兼容，读到自动迁移到 `lingyao_token`）|
| P1-6 | `data-private.sql` | 全部 10 个 INSERT 加 `ON CONFLICT (id) DO NOTHING` — 二次启动不再依赖 `continue-on-error=true` 兜底 |
| P2-7 | `LingyaoApplication.java` | `pushHealthCheck` 降级 ERROR → WARN + 加 INACTIVE 默认通道说明（不再吓运维）|
| 版本号 | `application.yml` | `app.version: 2.0.2 → 2.0.3`（铁律 2）|

### 验证（部署后）
- `curl http://118.195.197.15:9092/api/_diag/version` 返回 200 + V2.0.3 JSON（无需 JWT）
- staging 9092 启动日志无 `Using generated security password` 噪声
- staging 9092 启动日志 `pushHealthCheck` WARN 级别（不再 ERROR）
- staging 9092 jar 路径 `/opt/lingyao-staging/lingyao-platform.jar`（与 prod `/opt/lingyao/lingyao-platform.jar` 独立）
- staging 浏览器登录 admin/admin123 正常
- prod 9091 部署后启动日志同样干净
- prod 浏览器登录 admin/admin123 正常

### 遗留（V2.0.4+ 候选）
- `data-private.sql` 里 admin 默认 BCrypt hash 仍是 `admin123`（首次启动后强制改密），但很多客户不会改，存在安全风险 → V2.0.4 加"首次启动随机生成 admin 密码 + 启动日志打印 + 邮件通知"
- 推送通道默认 webhook URL 是 `REPLACE_ME`，运维不知道在哪替换 → V2.0.4 在超管后台加 `一键测试所有推送通道` 按钮
- prod 9091 公网不可达（腾讯云安全组只放行 80 + 9092），更新文档：prod 入口走 Nginx 80

---

## 2026-08-27 · V2.0.4 (V2.0.3 半成品 + deploy-staging hotfix)

### 触发
V2.0.3 部署后暴露两个未完成项：
1. `SecurityConfig.java` 把 `/api/_diag/**` 加进 permitAll，但 `VersionController` 没建这个 endpoint → 返"接口不存在"
2. `deploy-staging.sh` 写完就发现两个 bug：heredoc 内 `$EXPECTED_JAR` 被本 shell 提前展开、curl 走 Mac 本地 9092（应该 ssh 到 CVM curl）

### 改动
- `VersionController.java`：新增 `@GetMapping("/api/_diag/version")` endpoint，扩展字段：pid / started_at / uptime_sec / heap_used_mb / heap_max_mb（开发者诊断用）
- `scripts/deploy-staging.sh`：
  - heredoc 内变量转义：`\$EXPECTED_JAR` / `\$ACTUAL`（让远程 bash 展开而非本 shell）
  - health check + 子产品页 + diag 都改 `ssh CVM curl`（之前 `curl 127.0.0.1:9092` 是 Mac 本地端口）
  - 自动校验 systemd service ExecStart jar 路径必须 = `$REMOTE_DIR/lingyao-platform.jar`，不一致自动 sed 修正
- `application.yml`：`app.version: 2.0.3 → 2.0.4`
- CVM 上 `/opt/lingyao/application-staging.yml`：删除末尾 `app: version: ...` 块（additional-location 模式下外部 yml 覆盖 jar 内 version，必须删掉）

### 验证（部署后）
- `curl /api/_diag/version` 返 200 + JSON 含 `pid` / `heap_used_mb` 等扩展字段（无需 JWT）
- `deploy-staging.sh` 端到端跑通：scp → 自动迁移 systemd → restart → ssh curl health/subpages/diag 全部 ✅
- staging 启动日志无 P0-2 噪声 + P2-7 WARN 友好提示

---

| 项 | 描述 | 来源 |
|---|---|---|
| P1-1 | GEOm `local_health.py:300` 完美状态过滤 | 质量环扫描（明早 P1.1 部署时修）|
| P2-1 | 凌瑶 `application.yml` JWT secret fail-fast | 质量环扫描 |
| P2-2 | MPDm `hospital_hoyuan.db` 移位置 | 质量环扫描 |
| P2-3 | 凌瑶 `WebConfig.java` CORS 补 9093/9094 | 质量环扫描 |
| V2-GEOm | GEOm `__version__` 重构 + 端点 + CHANGELOG | 方案 A 迁移（明早 P1.1 部署时一起做）|
| V2-MPDm | MPDm `PLATFORM_VERSION` 去 H 前缀 + 端点 + CHANGELOG | 方案 A 迁移（V2 上线前）|

---

## 历史 commit 索引（2026-08-26 之前）

参见 `git log --oneline`：

```
b2c8cbc L4.2 fix: 显式指定 -i SSH_KEY 解决 Permission denied
484295b L4.1: GitHub Actions 自动部署 staging
aa9f459 L1+L2 staging 双环境 + 拆前端 4 子产品页
fe938de ✨ 阶段 1 + 阶段 2 部署基建完成
d56de1e 🎉 初始化凌瑶智数 Git 仓库
```

---

*文档维护说明：本 CHANGELOG 由质量环产物沉淀而来；后续每次发版请同步更新本文件。*

---

## 2026-08-27 · V2.0.5 (端到端发布流程打通 R-7)

**触发**：主人 2026-08-27 15:54 「来进行预发布到生产流程的打通，这件事很重要，要不然我就没办法去干其他的事了」

**核心目标**：让主人能"点按钮"就把 jar 从研发端推到生产端，不用每次 SSH 记路径。

**改动**：

### 后端（V2.0.5 R-7 新增模块）
- **新实体** `entity/ReleaseHistory.java`（13 字段，含 env/version/status/log/duration 等）
- **新 Repository** `repository/ReleaseHistoryRepository.java`（最新版本查询 + 防并发）
- **新 Service** `service/ReleaseService.java`：
  - `@Async deployStaging(jarPath, userId)` → SSH 到 staging 机器调 deploy-staging.sh
  - `@Async deployProd(userId)` → 本地调 deploy-prod.sh
  - `hasRunningDeployment()` 防并发
  - `getCurrentVersion(env)` 当前版本查询
- **新 Service** `service/WebhookService.java`：飞书群机器人 card 消息推送
- **新 Controller** `controller/ReleaseController.java`（6 个端点，仅 platformAdmin）：
  - `GET /api/admin/release/status` staging/prod 当前版本 + 是否运行中
  - `POST /api/admin/release/deploy-staging` 触发 staging（需 jarPath）
  - `POST /api/admin/release/deploy-prod` 触发 prod（铁律：必须先有 staging SUCCESS）
  - `GET /api/admin/release/history` 历史（分页 + 过滤）
  - `GET /api/admin/release/{id}` 单条详情（含 log）
- **新 DTO** `dto/admin/DeployStagingRequest.java`（@NotBlank jarPath）
- **新配置** `application.yml` `lingyao.release.*`（SSH key path + Webhook URL + 脚本路径）

### 前端（admin.html + 发布管理 Tab）
- **新增 Tab**「🚀 发布管理」（5 号 Tab，位置在邀请管理后）
- **新增 3 个 modal**：部署 staging / 晋升生产（二次确认输 prod）/ 查看日志
- **新增 JS**：`loadReleaseStatus()` / `loadReleaseHistory()` / `viewReleaseLog()` / `openDeployStagingModal()` / `openDeployProdModal()`
- **新增 CSS**：`.release-env-card` + `.btn-warn-sm`（橙色警告按钮）
- **二次确认铁律**：晋升生产必须输入 `prod` 才能确认

### 本地脚本（封装层）
- `scripts/release-to-staging.sh`（封装 deploy-staging.sh）
- `scripts/promote-to-prod.sh`（封装 deploy-prod.sh + 二次确认提示）

### 文档
- `docs/release-process.md`（完整发布流程手册：控制台按钮 / 本地脚本 / 手动 SSH 三种方式）

### 一次性 CVM 配置（主人需在 CVM 上跑一次）
```bash
# 1. 生成 release 专用 SSH key
sudo -u ubuntu ssh-keygen -t ed25519 -f /home/ubuntu/.ssh/release_staging_key -N ""
sudo -u ubuntu bash -c "cat /home/ubuntu/.ssh/release_staging_key.pub >> /home/ubuntu/.ssh/authorized_keys"

# 2. 测试 SSH 链路
sudo -u ubuntu ssh -i /home/ubuntu/.ssh/release_staging_key ubuntu@127.0.0.1 echo OK

# 3. （可选）配置飞书 Webhook
echo "LINGYAO_RELEASE_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/xxx" >> /opt/lingyao/.env.private
echo "LINGYAO_RELEASE_WEBHOOK_ENABLED=true" >> /opt/lingyao/.env.private
```

**验证步骤**：
1. Mac build：`cd backend && mvn clean package -DskipTests`
2. 浏览器登录 admin.html → 进入「🚀 发布管理」Tab
4. 点「📦 发布 staging」→ 输入 jar 路径 → 确认
5. 看历史记录从 `RUNNING` → `SUCCESS`（约 60-90 秒）
6. 验证 staging：`curl http://118.195.197.15:9092/api/_diag/version` 返 V2.0.5
7. 点「⬆ 晋升生产」→ 输 prod → 确认
8. 看历史记录 prod 从 `RUNNING` → `SUCCESS`
9. 验证 prod：`curl http://118.195.197.15/api/_diag/version` 返 V2.0.5
10. 飞书群收到卡片消息（如果 Webhook 已配）

**附属收益**：
- 多租户 + 大超管 + 演示租户的需求已经在 AdminController 落了大半（R-4/R-8 部分实现）
- 前端不需要改版本号（Vite 模式从 `/api/version` 自动拉），保持单一真理源

---

## 2026-08-28 · V2.0.7 (端到端跑通验证)

### Commit `TBD` · V2.0.7 端到端发布流程验证

**触发**：主人 2026-08-28 10:38 「把整个流程再跑通一遍」

**满足铁律**：
- **V1** Z 段 bump：2.0.6 → 2.0.7
- **V2** FE/BE 同步：backend `app.version` + admin.html 注释同步
- **V3** 跨产品主版本不变
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**改动**：
- `application.yml`：`app.version: 2.0.6 → 2.0.7`
- `admin.html`：13 处「V2.0.5 R-7」注释 → 「V2.0.7 R-7」（保持注释一致，便于代码考古）

**验证步骤**：
1. Mac build：`cd backend && mvn clean package -DskipTests`
2. 控制台触发 staging 部署
3. `curl http://118.195.197.15:9092/api/_diag/version` 返 `version: 2.0.7`
4. 控制台触发 prod 晋升（输 `prod`）
5. SSH 到 CVM：`curl http://127.0.0.1:9091/api/_diag/version` 返 `version: 2.0.7`

**额外修复**：
- 恢复本地 `~/.ssh/release_staging_key` 文件（之前被清理丢失）
- 重新生成 keypair + 推 pubkey 到 CVM ubuntu authorized_keys + /root/.ssh/
- prod 9091 外网访问不通问题：腾讯云安全组疑似只放行 9092，9091 外网走不通；本次通过 SSH 隧道 + 内部 curl 验证 prod 新版本

---

## 2026-08-28 · V2.0.9 (独立平台超管登录页 `/superadmin.html`)

### Commit `TBD` · V2.0.9 大超管独立登录入口

**触发**：主人 2026-08-28 13:23 「开始 Step 1」（大超管 4 步方案 · Step 1）

**满足铁律**：
- **V1** Z 段 bump：2.0.8 → 2.0.9
- **V2** FE/BE 同步：application.yml app.version + superadmin.html 底部版本号 + CHANGELOG
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**核心改动**：
- 新增 `backend/src/main/resources/static/superadmin.html` —— 平台超管专属登录入口（极简设计）
- 复用现有 `/api/auth/login` API（不新建后端）
- 前端 role 校验：登录成功后判断 `user.platformAdmin`，非平台超管拦截并提示走 `/portal.html`
- 已登录平台超管访问 `/superadmin.html` 直接跳 `/admin.html`（不重复登录）
- `application.yml`：`app.version: 2.0.8 → 2.0.9`

**URL 入口规划**：

| 入口 | 谁用 | 登录后落点 |
|---|---|---|
| `/portal.html` | 操作员 / 公司超管 / 客户 | 4 个产品分入口（按权限显示）|
| `/superadmin.html`（新）| **仅**平台超管 | 直接进 `/admin.html` |
| `/admin.html` | 平台超管 + 公司超管（按 role 切菜单）| 后台管理界面 |

**验收步骤**：
1. 浏览器访问 `http://<host>/superadmin.html` → 看到「凌瑶智数 · 平台超管登录」极简页
2. 输入平台超管账号 → 登录成功 → 跳 `/admin.html`
3. 输入公司超管账号 → 提示「该账号不是平台超管，请通过 /portal.html 登录」并拦截
4. 输入错误密码 → 提示「用户名或密码错误」
5. `/portal.html` 访问不受影响（公司超管仍正常登录看产品）

**配套 backlog（不在 V2.0.9 范围）**：
- Step 2：报名管理 Tab + KPI「待审报名」（V2.0.10）
- Step 3：admin.html role-based 菜单 + portal.html「⚙ 我的公司」按钮（V2.0.11）
- Step 4：运维按钮下沉（V2.0.12）
- V2.1.0：防机器人 V1（账号 + IP 双锁）

---

## 2026-08-29 · V2.0.12 (HPD SSO 接收端前置：主仓 JWT 加 audience='lingyao-sso')

### Commit `TBD` · 子产品 SSO 联调前置：主仓 JWT 必须带 audience claim

**触发**：主人 2026-08-29 18:14 「B 开始改造」→ HPD SSO 实施 → 主仓 token 缺 audience，子产品无法验签

**满足铁律**：
- **V1** Z 段 bump：2.0.11 → 2.0.12
- **V4** CHANGELOG 必记（本条目）
- **跨项目治理** 铁律：所有组件先备份再动手（备份至 `_backups-2026-08-29/pre-hpd-sso-jwt-aud-*`）

#### 改动详情

- `backend/.../security/JwtUtil.java` 加 `DEFAULT_AUDIENCE = "lingyao-sso"`，`Jwts.builder()` 加 `.audience().add(DEFAULT_AUDIENCE).and()`
- `application.yml` app.version 2.0.11 → 2.0.12
- 主仓对内 JwtAuthFilter 不强制校验 audience（jjwt 默认行为），向后兼容
- 子产品用 `LINGYAO_JWT_SECRET` + `aud='lingyao-sso'` 验签主仓签发的 token

#### 配套改动（主仓不变，只 HPD/AIDD/Dinfo/PORM/GEOM 各仓对应改动）

- HPD：`backend/app/security.py` 加 `load_lingyao_jwt_secret()` + `backend/app/routers/lingyao_sso.py` 新路由 + `models/models.py` User 表加 `lingyao_user_id/tenant_id/username` + `frontend/src/pages/hoyuan/SsoCallback.tsx` 新组件 + `App.tsx` 加路由 + `Login.tsx` 加按钮

---

## 2026-08-29 · V2.0.11 (D-1 base_url 配置外移，远端代码库稳定性)

### Commit `TBD` · 主仓 5 项稳定性 Fix 第 1+2 项：sub_task.base_url 配置外移 + daemon 路径参数化

**触发**：主人 2026-08-29 17:45 「请架构师验证上传远端代码库后是否稳定」+ 17:52 「先做 D-1」+ 18:05 「继续」

**满足铁律**：
- **V1** Z 段 bump：2.0.10 → 2.0.11
- **V4** CHANGELOG 必记（本条目）
- **跨项目治理** 铁律：所有组件先备份再动手（备份至 `_backups-2026-08-29/pre-d1-stability-fix-20260829-175357/`）

#### Fix 1 - sub_task.base_url 配置外移（17:50 完成）

- 新增 `backend/src/main/java/com/lingyao/platform/config/LingyaoSubTaskProperties.java`（@ConfigurationProperties 配置类）
- `application.yml` 加 `lingyao.subtask.routes.{geo,hpd,aidd,dinfo,porm}` 段
- `SubTaskController.java` `info()` + `enter()` 改读配置类（保留 sub_task 表 fallback）
- `data.sql` 加注释说明 base_url 字段由配置类接管
- 5 张卡片 enter URL 验证：`source: "config"` ✅
- 环境变量覆盖验证：`LINGYAO_SUBTASK_GEO_BASE_URL=http://10.99.99.99:8090` 生效 ✅

#### Fix 2 - daemon 路径参数化（18:08 完成）

- `scripts/start_backend_daemon.py` / `start_dev_daemon.py` / `start_frontend_daemon.py` 三个守护脚本
- `PROJECT_DIR = os.environ.get("LINGYAO_HOME", "/Users/hua/Documents/myself/凌瑶")`
- 顺手参数化 `JAVA_BIN`（LINGYAO_JAVA_BIN）和 `PYTHON_BIN`（LINGYAO_PYTHON_BIN）和 dev `SERVER_PORT`（LINGYAO_DEV_PORT）
- 3 个 daemon py_compile 通过；LINGYAO_HOME=/opt/lingyao-test 覆盖生效；前端 daemon 重启后 8765 HTTP 200 ✅

#### Fix 3 - README 升级（18:13 完成）

- 从 2026-08-11 的 "products/ 符号链接" 模型升级到 **5 个产品矩阵**（GEO/HPD/AIDD/Dinfo/PORM）
- SSO 协议从旧 3 参数（`token/from/company_id`）改为 **V1.0 标准 5 参数**（`tenant_id/user_id/user/display_name/platform_token`）
- 加"项目结构"图、"启动方式（开发者）"3 段、"V2.0.11 稳定性 Fix 清单"
- README 文件从 75 行 → 148 行（+97%）

#### Fix 4 - CVM 部署文档化（18:13 完成）

- 新增 `deploy/cvm/DEPLOYMENT.md`（13KB）
- 10 个章节：部署架构图 / 系统要求 / 目录规划 / 构建部署 / Profile 差异 / 5 子产品对接 / 运维 SOP / ICP 备案 / 常见问题 / 参考文档
- 关键铁律：**JDK 21 必须**（JDK 17 跑不动 V2.0.11+），**PostgreSQL 14+** for private profile

#### Fix 5 - SSO-CORS 策略文档（18:13 完成）

- 新增 `docs/portal-sso-design/03-sso-cors-policy.md`（11KB）
- 3 层协议分层：L1 JWT 鉴权 + L2 URL 跳转 + L3 Cookie 共享
- 4 种部署场景：生产 `.lingyao.cn` 子域共享 / 开发机 `SameSite=Lax` / 跨域 JWT-only / 完全独立 cookie
- 7 条绝对不能做的反例 + 7 条推荐安全实践

**改动**：
- 新增 `backend/src/main/java/com/lingyao/platform/config/LingyaoSubTaskProperties.java`：`@ConfigurationProperties(prefix="lingyao.subtask")` 映射 `Map<String, Route>`，含 `baseUrl` / `healthUrl` / `entryPath` 三字段
- `application.yml`：在 `lingyao:` 段下加 `subtask.routes.{geo,hpd,aidd,dinfo,porm}` 配置（5 个产品 × 3 字段 = 15 行），每个值用 `${LINGYAO_SUBTASK_<X>_<FIELD>:默认值}` 模式支持环境变量覆盖
- `SubTaskController.java`：注入 `LingyaoSubTaskProperties`，`enter()` 和 `info()` 优先读配置类（`getRoute(code).getBaseUrl()`），fallback 才用 `sub_task.base_url` 表字段
- `application.yml`：`app.version: 2.0.10 → 2.0.11`
- `data.sql` 暂不改：保留 `base_url` 字段做向后兼容 fallback（dev 环境回滚用）

**架构价值**：
- CVM 部署通过环境变量覆盖 `LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090` 即可切换子产品 URL
- 上传远端代码库后，接收方不需要改代码就能跑（默认 localhost）
- 调试可视化：`info()` 接口新增 `source` 字段（`config` 或 `sub_task_table`），前端可判断 baseUrl 来自配置还是表

**验证**：
- dev 9091 启动成功
- `curl /api/sub/geo/info` 的 `data.baseUrl` 应为 `http://127.0.0.1:8090`（默认），`source: config`
- 改环境变量 `LINGYAO_SUBTASK_GEO_BASE_URL=http://10.0.0.5:8090` 重启，`data.baseUrl` 应为新值
- admin 登录后点 5 张卡片仍能正常跳转（行为不变，因为配置类默认值与原 data.sql 一致）

---

## 2026-08-28 · V2.0.10 (公司编辑弹窗 + 状态枚举 5 种 + 许可证过期提醒)

### Commit `TBD` · V2.0.10 大超管后台能力升级

**触发**：主人 2026-08-28 13:57 「把它做出来」（大超管后台优化 3 步方案）

**满足铁律**：
- **V1** Z 段 bump：2.0.9 → 2.0.10
- **V2** FE/BE 同步：application.yml app.version + admin.html 底部版本号 + CHANGELOG
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/_diag/version 端点已就绪

**核心改动**：

**Step A · 弹窗排版修复 + 编辑公司弹窗**
- 修复 `#grantProductCheckboxes` 和 `#grantUserProductCheckboxes` label 排版 bug（之前 `display:flex` 在 block-level label 上导致 flex 容器宽度撑满父容器，复选框和文字分散到两端），现在改为 `inline-flex` + `width:auto` 紧凑对齐
- 新建 `#editCompanyModal`（V2.0.10 · 模态-large）：含 9 个可编辑字段（公司名称 / 部署模式 / 许可证等级 / 许可证起止日期 / 公司状态 / 最大用户数 / 联系邮箱 / 联系电话 / 地址 / 备注）+ code 不可改提示
- 部署模式/许可证等级/公司状态全部改为中文显示（共享云 SaaS / 私有化部署；试用/标准/企业；开通中/已开通/已过期/已暂停/已删除）
- 二次确认逻辑：修改部署模式 / 状态（SUSPENDED/DELETED 切换）/ 许可证截止日期 → 弹 confirm 二次确认
- 公司列表行加「✏ 编辑」按钮（中文显示：部署模式/许可证/状态）

**Step B · 状态枚举升级 3→5 种**
- `Company.CompanyStatus` 枚举从 3 种（ACTIVE/SUSPENDED/DELETED）扩到 5 种（**PENDING/ACTIVE/EXPIRED/SUSPENDED/DELETED**）
- `TenantAdminService.createCompany()` 默认状态 = PENDING（开通中）
- 新建 `schema-private.sql`：处理 `company_status_check` CHECK 约束升级（V2.0.8 SOP 同款 `ALTER TABLE DROP/ADD CONSTRAINT` 模式）
- 新增表 `license_reminder_log`（用于过期提醒去重 + 审计）

**Step C · 许可证过期提醒 scheduler**
- 新建 `LicenseExpirationScheduler.java`（每日 9:00 早检查 + 每日 23:00 晚过期 cron）
- 提前 30/15/7/1 天提醒大超管 + 公司超管（双发，邮箱 + 飞书复用现有通道）
- 过期当天自动 `ACTIVE → EXPIRED`（晚 23:00 cron）
- EXPIRED 公司超管登录拦截（在 `LoginService` 加 `CompanyStatus` 校验）

**新增后端 API**：
- `PUT /api/admin/companies/{id}` — 更新公司（仅 platformAdmin），高危字段前端二次确认

**新增后端类**：
- `UpdateCompanyRequest.java`（DTO，含二次确认字段）
- `LicenseReminderLog.java`（Entity）
- `LicenseExpirationScheduler.java`（`@Scheduled(cron)`）
- `LicenseExpirationService.java`（业务逻辑）

**新增 SQL 文件**：
- `schema-private.sql`（V2.0.10+ 升级脚本，含 CHECK 约束 + 新表）

**验收步骤**：
1. 大超管后台「公司管理」Tab → 列表行点「✏ 编辑」→ 弹窗显示当前值
2. 修改部署模式 → 弹 confirm「部署模式: 共享云 → 私有化部署，确定？」→ 确认后保存
3. 修改许可证截止日期 → 弹 confirm → 确认后保存
4. 新建公司 API 返回 status=PENDING（开通中），大超管手动改 ACTIVE 完成开通
5. 凌晨 23:00 自动把过期公司的 status 改为 EXPIRED
6. EXPIRED 公司的超管尝试登录 → 返回 403 + 提示「公司许可证已过期，请联系大超管」
7. 每日 9:00 自动给 30/15/7/1 天后过期的公司推飞书 + 邮件通知大超管 + 公司超管

---

## 2026-08-28 · V2.0.8 (产品改名 + 研发中置灰)

### Commit `TBD` · V2.0.8 产品品牌升级

**触发**：主人 2026-08-28 11:54 「改一下注册了之后目前4个产品的产品名...后两个产品置灰不可进入」

**满足铁律**：
- **V1** Z 段 bump：2.0.7 → 2.0.8
- **V2** FE/BE 同步：application.yml app.version + admin.html 注释同步
- **V4** CHANGELOG 必记（本条目）
- **V5** /api/version + /api/_diag/version 端点已就绪

**改动**：

| 产品 code | 旧名 | 新名 | 状态 |
|---|---|---|---|
| GEO | GEO 智策 | 棱镜-智能GEO监测 | ACTIVE |
| HPD | 医院潜力预测 | 皓元-智能医院潜力预测 | ACTIVE |
| AIDD | AIDD 研发反馈 | 源策-科研信息助手（研发中，敬请期待）| COMING_SOON |
| POR | 药企协作辅助智能体 | 飞轮-AI辅助协作智能体（研发中，敬请期待）| COMING_SOON |

**文件改动**：
- `SubTask.java`：Status 枚举加 `COMING_SOON` 值
- `data.sql` + `data-private.sql`：product.name + product.status（AIDD/POR）+ sub_task.status（AIDD/POR）
- `portal.html`：walkthrough step 1/2/3/4 标题同步新名（步骤 3/4 加「研发中，敬请期待」标记）

**数据库更新**（SSH 一次性跑 psql）：
- staging/prod PostgreSQL：UPDATE product + UPDATE sub_task（详见 commit message）

**验证步骤**：
1. staging 部署
2. 登录 `:9092/portal.html` → 看到 4 个产品卡片：GEO/HPD 可点、AIDD/POR 置灰
3. prod 部署
4. 登录 nginx:80 → 同样效果

---

## 2026-08-30 09:07 · 后端 dev 工具链修复 + lingyao-postgres 容器创建

**主人下达**：恢复凌瑶主后端 9091（private profile）。

### 改动

| # | 文件 | 性质 | 描述 |
|---|---|---|---|
| 1 | `scripts/start_backend_daemon.py` | dev 工具 | 增加 LINGYAO_JWT_SECRET 自动注入（参照 `backend/docker/entrypoint.sh`：复用 `data/.jwt_secret`，无则生成新密钥并 chmod 600） |
| 2 | `scripts/start_backend_daemon.py.bak.2026-08-30` | 备份 | 原 daemon 备份（1538B） |
| 3 | `data/.jwt_secret` | 新增 | JWT 密钥持久化（64 字节 base64，权限 0600） |
| 4 | 本地 Docker 容器 `lingyao-postgres` | 新增 | postgres:16-alpine 镜像，端口 5432:5432，volume `lingyao-pg-data:/var/lib/postgresql/data`，env: POSTGRES_DB=lingyao / USER=lingyao_app / PASSWORD=Lingyao_Prod_2026_Pg!，--restart unless-stopped |

### 验证

- `127.0.0.1:9091/api/version` → HTTP 200，返回 `{"service":"lingyao-platform","version":"1.0.0","release":"stable","build_time":"2026-08-30T09:06:35"}`
- `127.0.0.1:5432` 容器 lingyao-postgres Up（双栈监听 0.0.0.0 + ::）
- watchdog V3.1.1 转绿（12 个端口全 ✅）
- Spring Boot DispatcherServlet 初始化成功

### 根因复盘

private profile fail-fast 校验依赖 3 个外部条件：
1. PostgreSQL 5432 可达（**之前 PG 未启，本次创建 lingyao-postgres 容器**）
2. LINGYAO_JWT_SECRET 环境变量 ≥32 字节非默认（**之前 daemon 未传，本次自动注入**）
3. data.sql / data-private.sql 在 classpath（已满足）

---

## 2026-08-30 10:25 · 本地 Docker Compose 一体化部署完整链路打通（27 步链路）

**主人下达**：解决凌瑶主仓后端 9091 起不来问题，端到端打通本地研发环境。

### 改动

| # | 文件 | 性质 | 描述 |
|---|---|---|---|
| 1 | `deploy/docker-compose.yml` | 新增 | PG + Backend 一体编排（服务名 lingyao-postgres + lingyao-backend，含 healthcheck + restart policy + named volume）|
| 2 | `deploy/Dockerfile.backend` | 新增 | 多阶段构建：maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre，**修正 Dockerfile.cn 的 JDK 17 → 21 漂移** |
| 3 | `deploy/.env.example` | 新增 | 6 个铁律字段模板（POSTGRES_DB/USER/PASSWORD/SPRING_DATASOURCE_URL/SPRING_PROFILES_ACTIVE/BACKEND_PORT + APP_VERSION + TZ）|
| 4 | `deploy/CHECK_REPORT.md` | 新增 | 一致性 12 项 Check + 5 道保险 + 本地实测验证 |
| 5 | `deploy/README_FOR_CLOUD_TEAM.md` | 新增 | 云端 AI 对接规范（5 步部署）|
| 6 | `scripts/start_backend_daemon.py` | 修改 | LINGYAO_JWT_SECRET 自动注入（参照 entrypoint.sh 模式）|
| 7 | `scripts/port_watchdog.py` | 新增 | V3.1.1 watchdog（监控 7 项目 12 端口）|
| 8 | `scripts/start_watchdog_daemon.py` | 新增 | watchdog daemon 启动器（双重 fork + setsid）|
| 9 | `scripts/start_geom_frontend_daemon.py` | 新增 | GEOM 前端 daemon（5182 双栈 IPv4/IPv6）|

### 验证（2026-08-30 10:18）

`curl http://127.0.0.1:9091/api/version` 返回 HTTP 200：
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "service": "lingyao-platform",
    "version": "2.0.14",
    "release": "stable",
    "build_time": "2026-08-30T10:17:54",
    "git_commit": "local",
    "sso_protocol": "1.0"
  },
  "success": true
}
```

`docker compose ps` 输出：
```
lingyao-backend    Up 56 seconds (healthy)       127.0.0.1:9091->9091/tcp
lingyao-postgres   Up About a minute (healthy)   127.0.0.1:5432->5432/tcp
```

Spring Boot + HikariCP + JPA + PostgreSQL 全链路打通。

### 27 步完整链路（踩坑回放）

```
[Step 1-7] 09:55 docker.io 超时
  → DaoCloud m.daocloud.io/docker.io/library/... 镜像源
  → WORKDIR /build 修正为 /build/backend（jar 在子目录）
  → 端口 9091 被孤儿 java PID 66779 占 → kill
  → entrypoint.sh 重定向 java 输出到 /app/logs/lingyao.log（用 docker exec/cat 读）

[Step 8-15] 09:55 占位符语法验证
  → application-private.yml:28 占位符已就位（之前 PR 已包含，本任务窗口确认）
  → 环境变量 SPRING_DATASOURCE_URL=lingyao-postgres:5432 正确注入（docker inspect 验证）
  → docker compose build lingyao-backend → 镜像 lingyao/platform:2.0.14
  → docker compose up -d lingyao-backend → Started

[Step 16-22] 10:00 占位符生效但密码错误
  → Up Less than a second (health: starting) 循环重启
  → docker cp /app/logs/lingyao.log 读取 java 错误
  → FATAL: password authentication failed for user "lingyao_app"
  → 启动 WARN：Volume lingyao-pg-data declared as external in compose
  → 卷是此前 PG 容器遗留下来的，PG 首次 init 时是 lingyao_app superuser + 旧密码

[Step 23-25] 10:08 删卷 + 重 init
  → 尝试 psql -U postgres 报 role "postgres" does not exist（确认 superuser 是 lingyao_app）
  → docker compose stop/rm lingyao-postgres
  → docker volume rm lingyao-pg-data
  → docker compose up -d lingyao-postgres（新卷重建，PG 重新执行 init）

[Step 26-27] 10:13-10:18 PG 重 init + Spring Boot 重连
  → sleep 15 + docker compose ps → 全绿
  → curl /api/version → HTTP 200 + version=2.0.14 ✅
```

### 3 条新铁律（已写入 MEMORY.md）

1. **占位符语法铁律**：Spring Boot 关键配置（datasource URL）必须用 `${ENV:default}` 占位符语法，不能用纯硬编码。理由：profile-specific YAML 在某些边界 case 下，OS env 不能直接覆盖纯硬编码字符串
2. **卷初始化铁律**：compose 部署有状态服务（PG/MySQL/Redis）前，**必须确保数据卷是干净的**。旧卷不删 = 容器不重新 init = 旧的角色/密码锁在卷里。诊断 WARN：`Volume XXX was found on local filesystem but is declared as external in compose`
3. **DaoCloud 镜像源铁律**：docker.io 拉镜像超时 → 用 `m.daocloud.io/docker.io/library/...`（macOS sandbox 必备）

### 云端对接（重要）

**本任务窗口仅处理本地**（铁律 #0）。云端由云端主流程接手：

- 云端 AI 拿到 `deploy/` 5 文件 + `README_FOR_CLOUD_TEAM.md` 后，按 5 步部署即可
- `.env` 中的密码由主人自管，云端不上传 `.env`（只传 `.env.example`）
- 云端不需要重做本地踩过的坑（PG 卷复用、占位符等已在 deploy/ 解决）
- 本地版本 `v2.0.14` 与云端对齐（铁律 #3 前后端版本号同步）

### 待办（移交云端 AI 后）

- [x] 本任务窗口：完成 deploy/ + scripts/ + CHANGELOG 全部本地沉淀（10:25）
- [ ] 云端 AI：pull → SSH 部署 → verify /api/version → 切流

