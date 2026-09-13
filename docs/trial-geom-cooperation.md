# 主站 Trial 与 GEOM 配合契约文档 · V2.0.78.1

> **文档目的**：主站（trial 流程主控方）已上线 V2.0.78.1 Day 1+Day 2。trial 期用户会登录 5 大子产品（GEO/HPD/AIDD/DINFO/PORM），主站需要从 GEOM 拉取 trial 用户的活动数据来计算健康度、生成周报/月报。本文档列出 **GEOM 需要新增/暴露的端点 + 数据字段**，由 GEOM 窗口接力实施。
>
> **版本**：V2.0.78.1（2026-09-13 17:40）· 主站窗口写
> **联络窗口**：主站 TrialRegistrationService / TrialHealthScoreService / TrialReportService

---

## 一、为什么需要 GEOM 配合？

### 主站已有信号源（V2.0.78.1 自给自足）
| 信号 | 来源表 | 字段 | 用途 |
|---|---|---|---|
| 注册时间 | trial_registration | created_at | 计算试用期剩余 |
| 试用用户名 | trial_registration | phone | 关联 sys_user |
| 公司 ID | trial_registration | company_id | 关联 Company |
| 邮件 | trial_registration | email | 发邮件目标 |
| 主站登录时间 | sys_user | last_login_at | 健康度"活跃度" |
| 主站登录次数 | sys_user | 简化为 1/2 占位 | **待 GEOM 端点替换** |

### 主站缺、需要 GEOM 提供（第二批 · 跨窗口）
| 信号 | 用途 | 主站缺什么 |
|---|---|---|
| **trial 用户在 GEOM 内的登录次数** | 健康度 score 计算（login_count > 3 = +30） | 仅看主站登录看不到 GEOM 内的活跃度 |
| **trial 用户在 GEOM 内的巡检次数** | 健康度 score（inspection_count > 1 = +40） | 主站巡检 ≠ GEOM 巡检 |
| **trial 用户在 GEOM 内的最近活跃时间** | 健康度 score（last_active_at ≤ 3 天 = +30） | 主站 last_active_at 不含 GEOM 操作 |
| **trial 用户在 GEOM 内的关键操作清单** | 周报/月报邮件内容 | 主站信号太粗，邮件里"本周巡检了 3 次，命中 2 个关键词"等需要 GEOM 数据 |

---

## 二、GEOM 需新增/暴露的 3 个端点

### 端点 1（推荐优先）· GET /api/users/trial-status

**用途**：主站健康度 score 计算时拉取 GEOM 端 trial 用户活动数据

```
GET /api/users/trial-status?phone=13800001111
Host: localhost:8090
Authorization: Bearer <service_jwt>     ← 主站用 service JWT 调（@Audience lingyao-service）
```

**响应**：
```json
{
  "phone": "13800001111",
  "trialUserId": 123,                   // GEOM 端 user.id（如果该用户已开通 GEOM）
  "loginCount": 5,                      // 累计登录次数（含 trial 期）
  "lastActiveAt": "2026-09-13T14:30:00",
  "lastInspectionAt": "2026-09-12T10:15:00",
  "inspectionCount": 2,                 // 累计巡检次数
  "keywordsTracked": 8,                 // 跟踪关键词数（可选 · 周报/月报用）
  "reportsViewed": 3                    // 报告查看数（可选 · 周报/月报用）
}
```

**实施方**：GEOM 仓
**优先级**：🔴 高（Day 2 健康度 score 计算接入后可丰富信号源）

### 端点 2 · POST /api/internal/trial/inspection-callback

**用途**：trial 用户在 GEOM 端完成一次巡检后，GEOM 回调主站登记（主站 trial_registration.first_inspection_at 已由 cron 写，但产品内操作明细可补）

```
POST /api/internal/trial/inspection-callback
Host: localhost:9091
Authorization: Bearer <service_jwt>
Content-Type: application/json

{
  "phone": "13800001111",
  "inspectionAt": "2026-09-13T15:00:00",
  "keywordsAnalyzed": 5,
  "reportId": "RPT-2026-0913-001"
}
```

**主站端处理**：写 trial_inspection_log 表（V2.0.78.2 Day 3 新增）+ 更新 trial_health_score.inspection_count
**实施方**：GEOM 仓（GEOM 在巡检完成时回调）+ 主站窗口（V2.0.78.2 Day 3 落接收端点）

### 端点 3 · GET /api/internal/trial/active-list（可选）

**用途**：主站周报/月报发送前，批量拉取 GEOM 端 trial 用户活跃数据（如果端点 1 走批量版，可代替端点 1）

```
GET /api/internal/trial/active-list
Host: localhost:8090
Authorization: Bearer <service_jwt>
```

**响应**：array of `trial-status` 对象
**实施方**：GEOM 仓
**优先级**：🟡 中（端点 1 实现后此端点可选）

---

## 三、主站侧的调用方式

主站用 **service JWT**（audience=lingyao-service）调用 GEOM 内部端点，避免暴露外部：
- service JWT 共享密钥：`LINGYAO_JWT_SECRET`（主人 09-11 部署前加固清单项目）
- 当前 `/api/internal/**` 在 SecurityConfig 已 permitAll + 自验 service JWT

**实施方（主站侧）**：V2.0.78.2 Day 3 落
- 新增 `GeomCooperationClient` Service
- 新增 `trial_inspection_log` 表
- 接入 TrialHealthScoreService.computeOne() 拉 GEOM 数据

---

## 四、时间线（双方协作节奏）

| 时间 | 主站 | GEOM |
|---|---|---|
| 2026-09-13 ✅ | Day 1 V2.0.78.0 trial 注册 | — |
| 2026-09-13 ✅ | Day 2 V2.0.78.1 健康度 + 首巡检 + 周报/月报 | — |
| 2026-09-13 📋 | **写本协作文档** | — |
| 2026-09-14+ ⏳ | — | 实施端点 1（GET trial-status） |
| 2026-09-15+ ⏳ | V2.0.78.2 Day 3 接入 GEOM 数据 | — |
| 2026-09-? ⏳ | — | 实施端点 2（inspection-callback） |

---

## 五、附录 · 当前 TrialRegistrationService 关键代码

主站侧已知 trial_registration 与 GEOM 关联位置：

```java
// TrialRegistrationService.java L91-104（V2.0.78.0）
String companyCode = ("TRIAL" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
Company company = new Company();
company.setName(req.getCompanyName());
company.setCode(companyCode);
company.setContactEmail(req.getEmail());
company.setContactPhone(req.getPhone());
company.setTrialExpiresAt(LocalDateTime.now().plusDays(TRIAL_DAYS));
company.setGeomPlan("trial");
company.setLicensePlan(Company.LicensePlan.TRIAL);
company.setMaxUsers(1);  // D-4 trial 契约：1 席位
```

GEOM 端可通过 company_code 或 contact_phone 反查 trial 公司 + 用户映射。

---

## 六、主人/GEOM 窗口 必读

- **主站窗口**：本窗口只动 `凌瑶/`，**不动 GEOM 仓代码**
- **GEOM 窗口**：请在 GEOM 仓内实施端点 1（GET /api/users/trial-status），完成后通知主站接入
- **协同密钥**：双方共享 `LINGYAO_JWT_SECRET` 环境变量（部署时由运维统一生成 + 分发）

**主站侧接手人**：花卷（MiniMax-M3）
**GEOM 侧接手人**：主人 09-13 17:31 后通知

---

*文档落盘位置：*
- `/Users/hua/Documents/myself/凌瑶/docs/trial-geom-cooperation.md`（主站仓 docs/）
- `/tmp/GEOM_trial_cooperation_2026-09-13.md`（跨窗口临时文档）

*版本：V2.0.78.1 · 2026-09-13 17:40*
