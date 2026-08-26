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
  (1, 'GEO', 'GEO 智策', 'AI 品牌监测与内容优化，监测 8 大 AI 平台', 'G', 1, 'ACTIVE', NOW(), NOW()),
  (2, 'HPD', '医院潜力预测', '医院销售潜力模型预测，WMAPE 6.6%', 'H', 2, 'ACTIVE', NOW(), NOW()),
  (3, 'AIDD', 'AIDD 研发反馈', 'AI 辅助药物研发信息反馈系统', 'A', 3, 'ACTIVE', NOW(), NOW()),
  (4, 'POR', '药企协作辅助智能体', '让药企内部效率提升 300%', 'P', 4, 'ACTIVE', NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- 公司产品授权（A 药企: GEO + HPD；B 药企: AIDD + POR）
-- ═══════════════════════════════════════════════════════════
INSERT INTO company_product (id, company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
VALUES
  (1, 1, 1, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (2, 1, 2, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (3, 2, 3, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW()),
  (4, 2, 4, NOW(), DATEADD('YEAR', 1, NOW()), 50, 'ACTIVE', NOW(), NOW());

-- ═══════════════════════════════════════════════════════════
-- 用户产品授权（A_admin 有 GEO+HPD 全权限；a_user1 只有 GEO）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (1, 1, 1, 2, 2, 'ACTIVE', NOW()),  -- a_admin 有 GEO
  (2, 1, 2, 2, 2, 'ACTIVE', NOW()),  -- a_admin 有 HPD
  (3, 1, 1, 3, 2, 'ACTIVE', NOW());  -- a_user1 只有 GEO

INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (4, 2, 3, 4, 4, 'ACTIVE', NOW()),  -- b_admin 有 AIDD
  (5, 2, 4, 4, 4, 'ACTIVE', NOW());  -- b_admin 有 POR

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
  (9, 4, 'SUPER_ADMIN', 'POR 超级管理员', '["*"]', 1, NOW()),
  (10, 4, 'OPERATOR', 'POR 操作员', '["read", "task"]', 2, NOW());

-- ═══════════════════════════════════════════════════════════
-- 产品内用户角色分配
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_role (id, company_id, product_id, user_id, role_code, assigned_by, created_at)
VALUES
  (1, 1, 1, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 GEO 内是超级管理员
  (2, 1, 2, 2, 'SUPER_ADMIN', 2, NOW()),    -- a_admin 在 HPD 内是超级管理员
  (3, 1, 1, 3, 'OPERATOR', 2, NOW()),       -- a_user1 在 GEO 内是操作员
  (4, 2, 3, 4, 'SUPER_ADMIN', 4, NOW()),    -- b_admin 在 AIDD 内是超级管理员
  (5, 2, 4, 4, 'SUPER_ADMIN', 4, NOW());    -- b_admin 在 POR 内是超级管理员

-- ═══════════════════════════════════════════════════════════
-- 子任务配置（4 个产品子任务接入点）
-- ═══════════════════════════════════════════════════════════
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'ACTIVE', 'GEO 品牌监测核心引擎（已对接 OEG 子任务）', 'http://127.0.0.1:8090', 'http://127.0.0.1:8090/api/health', NOW(), NOW()),
  (2, 2, '医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'REGISTERED', '医院销售潜力预测模型', NULL, NULL, NOW(), NOW()),
  (3, 3, 'AIDD 研发子任务', 'aidd-engine', '/subtask/aidd', '/api/sub/aidd', 'REGISTERED', 'AI 辅助药物研发引擎', NULL, NULL, NOW(), NOW()),
  (4, 4, '协作智能体子任务', 'por-agent', '/subtask/por', '/api/sub/por', 'REGISTERED', '药企协作智能体引擎', NULL, NULL, NOW(), NOW());

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
