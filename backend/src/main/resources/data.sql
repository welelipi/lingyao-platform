-- ═══════════════════════════════════════════════════════════
-- 凌瑶智数 · 初始化数据
-- 启动时由 Spring Boot 自动执行（H2 内存库）
-- ═══════════════════════════════════════════════════════════

-- 1. 平台超管账号（主人）
-- 用户名: admin   密码: admin123
-- password_changed=TRUE（SAAS 老用户已改密，不触发首登强制改密）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$sGGMda/K1XOkDq/gg5B18.Xu99jzot/vt/Et6k6KUi5NVa1hKM6hK', '平台超管', 'admin@lingyao.cn', 'ACTIVE', TRUE, TRUE, NOW(), NOW());

-- 2. 演示公司 A：购买了 GEO + 医院潜力预测
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (1, 'A 药企（演示）', 'A-DEMO', 'SAAS', 'ENTERPRISE', 'ACTIVE', 100, 'admin@a-pharma.cn', NOW(), NOW());

-- 3. 演示公司 B：购买了 AIDD + 协作智能体
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (2, 'B 药企（演示）', 'B-DEMO', 'SAAS', 'ENTERPRISE', 'ACTIVE', 100, 'admin@b-pharma.cn', NOW(), NOW());

-- 4. A 药企管理员（用户名: a_admin  密码: pass123）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, created_at, updated_at)
VALUES (2, 'a_admin', '$2a$10$7Jch2YNp3SLTl69yurfQ6efLHxf4O6pO/bbIgLHqvAbWFpfhkyHGu', 'A 药企管理员', 'admin@a-pharma.cn', 'ACTIVE', FALSE, TRUE, NOW(), NOW());

-- 5. A 药企普通用户（用户名: a_user1  密码: pass123）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, created_at, updated_at)
VALUES (3, 'a_user1', '$2a$10$7Jch2YNp3SLTl69yurfQ6efLHxf4O6pO/bbIgLHqvAbWFpfhkyHGu', 'A 药企用户 1', 'user1@a-pharma.cn', 'ACTIVE', FALSE, TRUE, NOW(), NOW());

-- 6. B 药企管理员（用户名: b_admin  密码: pass123）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, created_at, updated_at)
VALUES (4, 'b_admin', '$2a$10$7Jch2YNp3SLTl69yurfQ6efLHxf4O6pO/bbIgLHqvAbWFpfhkyHGu', 'B 药企管理员', 'admin@b-pharma.cn', 'ACTIVE', FALSE, TRUE, NOW(), NOW());

-- 7. 平台超管绑定到所有公司（跨租户管理）
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (1, 1, 1, 'SUPER_ADMIN', 'ACTIVE', NOW()),
       (2, 2, 1, 'SUPER_ADMIN', 'ACTIVE', NOW());

-- 8. A 药企管理员加入 A 公司
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (3, 1, 2, 'SUPER_ADMIN', 'ACTIVE', NOW());

-- 9. A 药企普通用户加入 A 公司
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (4, 1, 3, 'OPERATOR', 'ACTIVE', NOW());

-- 10. B 药企管理员加入 B 公司
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (5, 2, 4, 'SUPER_ADMIN', 'ACTIVE', NOW());

-- ═══════════════════════════════════════════════════════════
-- 产品目录
-- ═══════════════════════════════════════════════════════════
INSERT INTO product (id, code, name, description, icon, sort_order, status, created_at, updated_at)
VALUES
  (1, 'GEO', '棱镜-智能GEO监测', 'AI 品牌监测与内容优化，监测 8 大 AI 平台', 'G', 1, 'ACTIVE', NOW(), NOW()),
  (2, 'HPD', '皓元-智能医院潜力预测', '医院销售潜力模型预测，WMAPE 6.6%', 'H', 2, 'ACTIVE', NOW(), NOW()),
  (3, 'AIDD', '源策-AI 立项 Copilot', 'AI 辅助药物研发立项 Copilot（双种子方向：肿瘤 + 自免），含 CDE 中国监管数据 + 商业测算（PDB/米内）双增强', 'A', 3, 'ACTIVE', NOW(), NOW()),
  (4, 'DINFO', '辰录 · 智能信息填报系统', 'Dinfo 基于 GH-Department info V1.5.23 继承的 9 实体 + 9 Controller + 10 前端页面 + 飞书 OAuth + JWT 鉴权 + Excel 重载，新增 LLM 智能填写 + 多任务架构', 'D', 4, 'ACTIVE', NOW(), NOW()),
  (5, 'PORM', '明枢-PORM 智能协作平台', 'POR-M 透明 × 协作中枢（看得见的协作），从 POR/GS-CoLab 衍生 v1.0.0，每张表/每条工单/每次审批都可被看见、可被追溯', '明', 5, 'ACTIVE', NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- 公司产品授权（A 药企: GEO + HPD + AIDD + DINFO + PORM；B 药企: 仅 GEO）
-- ═══════════════════════════════════════════════════════════
INSERT INTO company_product (id, company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
VALUES
  (1, 1, 1, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (2, 1, 2, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (3, 1, 3, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (4, 1, 4, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (5, 1, 5, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (6, 2, 1, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- 用户产品授权（A_admin 有 GEO+HPD+AIDD+DINFO+PORM 全权限；a_user1 只有 GEO；B 平台 b_admin 只有 GEO）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (1, 1, 1, 2, 2, 'ACTIVE', NOW()),  -- a_admin 有 GEO
  (2, 1, 2, 2, 2, 'ACTIVE', NOW()),  -- a_admin 有 HPD（皓元-智能医院潜力预测）
  (3, 1, 1, 3, 2, 'ACTIVE', NOW()),  -- a_user1 只有 GEO
  (8, 1, 3, 2, 2, 'ACTIVE', NOW()),  -- a_admin 有 AIDD（AIDD 上架）
  (9, 1, 3, 1, 1, 'ACTIVE', NOW()),  -- 平台超管 admin 也有 AIDD
  (10, 1, 4, 2, 2, 'ACTIVE', NOW()), -- a_admin 有 DINFO（辰录 上架）
  (11, 1, 4, 1, 1, 'ACTIVE', NOW()), -- 平台超管 admin 也有 DINFO
  (12, 1, 5, 2, 2, 'ACTIVE', NOW()), -- a_admin 有 PORM（明枢 上架）
  (13, 1, 5, 1, 1, 'ACTIVE', NOW()), -- 平台超管 admin 也有 PORM
  (14, 2, 1, 4, 4, 'ACTIVE', NOW()), -- b_admin 有 GEO（B 公司换 GEO）
  (15, 1, 2, 1, 1, 'ACTIVE', NOW()); -- 平台超管 admin 也有 HPD

-- ═══════════════════════════════════════════════════════════
-- 产品内角色
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_role (id, product_id, role_code, role_name, permissions, sort_order, created_at)
VALUES
  (1, 1, 'SUPER_ADMIN', 'GEO 超级管理员', '["*"]', 1, NOW()),
  (2, 1, 'SENIOR_OPERATOR', 'GEO 高级操作员', '["read", "write", "diagnose"]', 2, NOW()),
  (3, 1, 'OPERATOR', 'GEO 操作员', '["read", "write"]', 3, NOW()),
  (4, 1, 'VIEWER', 'GEO 查看者', '["read"]', 4, NOW()),
  (5, 2, 'SUPER_ADMIN', 'HPD 超级管理员', '["*"]', 1, NOW()),
  (6, 2, 'OPERATOR', 'HPD 操作员', '["read", "predict"]', 2, NOW()),
  (7, 3, 'SUPER_ADMIN', 'AIDD 超级管理员', '["*"]', 1, NOW()),
  (8, 3, 'OPERATOR', 'AIDD 操作员', '["read", "search"]', 2, NOW()),
  (11, 4, 'SUPER_ADMIN', 'DINFO 超级管理员', '["*"]', 1, NOW()),
  (12, 4, 'OPERATOR', 'DINFO 操作员', '["read", "write", "fill"]', 2, NOW()),
  (13, 5, 'SUPER_ADMIN', 'PORM 超级管理员', '["*"]', 1, NOW()),
  (14, 5, 'OPERATOR', 'PORM 操作员', '["read", "task"]', 2, NOW());

-- ═══════════════════════════════════════════════════════════
-- 产品内用户角色分配
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_role (id, company_id, product_id, user_id, role_code, assigned_by, created_at)
VALUES
  (1, 1, 1, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 GEO 内是超级管理员
  (2, 1, 2, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 HPD 内是超级管理员
  (3, 1, 1, 3, 'OPERATOR', 2, NOW()),       -- a_user1 在 GEO 内是操作员
  (6, 1, 3, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 AIDD 内是超级管理员
  (7, 1, 4, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 DINFO 内是超级管理员
  (8, 1, 5, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 PORM 内是超级管理员
  (9, 1, 5, 1, 'SUPER_ADMIN', 1, NOW()),    -- 平台超管 admin 在 PORM 内是超级管理员
  (10, 1, 3, 1, 'SUPER_ADMIN', 1, NOW()),   -- 平台超管 admin 在 AIDD 内是超级管理员
  (11, 1, 4, 1, 'SUPER_ADMIN', 1, NOW()),   -- 平台超管 admin 在 DINFO 内是超级管理员
  (12, 1, 2, 1, 'SUPER_ADMIN', 1, NOW());   -- 平台超管 admin 在 HPD 内是超级管理员

-- ═══════════════════════════════════════════════════════════
-- 子任务配置（5 个产品子任务接入点：GEO/HPD/AIDD/DINFO/PORM）
-- V2.0.11 起 base_url / health_url 优先读 application.yml 的 lingyao.subtask.routes.<code>
-- 字段保留作 fallback（dev 环境快速回滚用），生产环境强烈建议用环境变量覆盖
-- ═══════════════════════════════════════════════════════════
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'ACTIVE', 'GEO 品牌监测核心引擎（已对接 OEG 子任务）· V0.9.12.32 C47 W5 P1-SSO 接入凌瑶主站', 'http://localhost:5180/#/auth/lingyao/sso-callback', 'http://127.0.0.1:8090/api/health', NOW(), NOW()),
  (2, 2, 'HPD 医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'ACTIVE', 'HPD 智能医院潜力预测（指向 MPD-myself 独立站 http://localhost:3100）', 'http://localhost:3100/#/sso/callback', 'http://localhost:3100/api/health', NOW(), NOW()),
  (3, 3, 'AIDD 研发子任务', 'aidd-engine', '/subtask/aidd', '/api/sub/aidd', 'ACTIVE', 'AI 立项 Copilot 双种子方向（肿瘤 + 自免），CDE + 商业测算双增强（C47 W5 P1-SSO 已对接）', 'http://localhost:13000/sso-callback.html', 'http://localhost:18080/api/_diag/version', NOW(), NOW()),
  (4, 4, '辰录 DINFO 填报子任务', 'dinfo-fill', '/subtask/dinfo', '/api/sub/dinfo', 'ACTIVE', 'Dinfo 智能信息填报系统（基于 GH-Department info V1.5.23），含 LLM 智能填写 + 多任务架构 · D1.1.0 C47 W5 P1-SSO 接入凌瑶主站', 'http://localhost:5181/auth/lingyao/callback', 'http://localhost:8281/api/_diag/version', NOW(), NOW()),
  (5, 5, '明枢 PORM 协作子任务', 'porm-collab', '/subtask/porm', '/api/sub/porm', 'ACTIVE', 'POR-M 明枢智能协作平台（v1.0.0，源头 GS-CoLab v0.4.0+），透明 × 协作中枢 · v0.8.152 C47 W5 P1-SSO 接入凌瑶主站', 'http://localhost:8280/api/sso/callback-redirect', 'http://localhost:8280/api/_diag/version', NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- 默认推送通道配置
-- ═══════════════════════════════════════════════════════════
INSERT INTO notification_channel (id, channel_type, name, webhook_url, status, sort_order, created_at, updated_at)
VALUES
  (1, 'FEISHU', '飞书群机器人（默认）', 'https://open.feishu.cn/open-apis/bot/v2/hook/REPLACE_ME', 'INACTIVE', 1, NOW(), NOW()),
  (2, 'WECHAT_WORK', '企微群机器人（默认）', 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=REPLACE_ME', 'INACTIVE', 2, NOW(), NOW()),
  (3, 'WECHAT_MP', '微信公众号模板消息', '', 'INACTIVE', 3, NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- IDENTITY 自增偏移（修复 Bug-22 redeem 时 Hibernate IDENTITY 与显式 id 冲突）
-- 让 H2 自增从 1000 开始，彻底避免与 data.sql 中的 1-15 冲突
-- ═══════════════════════════════════════════════════════════
ALTER TABLE sys_user ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE company ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE company_user ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE product ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE company_product ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE product_role ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE product_user_grant ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE product_user_role ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE registration ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE notification_channel ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE sub_task ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE company_audit_log ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE invitation ALTER COLUMN id RESTART WITH 1000;
