-- ═══════════════════════════════════════════════════════════
-- 凌瑶智数 · dev-pg 持久化开发库初始化数据（V2.0.53）
--
-- 主人 2026-09-03 拍板：dev 切 PG 持久化——界面业务口径修改重启保留
--
-- 设计原则（与 staging/prod 的 data-private.sql 同风格）：
-- - 全部 INSERT 带 ON CONFLICT (id) DO NOTHING → 幂等，
--   已存在的行（含界面改过的状态）重启时跳过，绝不覆盖
-- - PG 方言（NOW() + INTERVAL / ON CONFLICT）
-- - 数据口径与 dev(H2) 的 data.sql 完全一致（V2.0.52 版口径）：
--   A 药企仅 GEO（主人 09-03 拍板）/ B 药企仅 GEO / 5 产品目录
-- ═══════════════════════════════════════════════════════════

-- 1. 平台超管账号（主人）
-- 用户名: admin   密码: admin123
-- password_changed=TRUE（不触发首登强制改密）
-- V2.0.77.5（主人 2026-09-07 拍板）：admin email 改 188407408@qq.com（主人期望邮箱，
--   主流程接收设备验证码/安全告警邮件）。修复链路：admin@lingyao.cn → 188407408@qq.com
--   （dev/H2 的 data.sql V2.0.77.2 已修；dev-pg/PG 库 V2.0.77.5 同步修；private/PG 私有化 V2.0.77.5 同步修）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, two_factor_enabled, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$sGGMda/K1XOkDq/gg5B18.Xu99jzot/vt/Et6k6KUi5NVa1hKM6hK', '平台超管', '188407408@qq.com', 'ACTIVE', TRUE, TRUE, FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. 演示公司 A（V2.0.52 口径：仅 GEO）
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (1, 'A 药企（演示）', 'A-DEMO', 'SAAS', 'ENTERPRISE', 'ACTIVE', 100, 'admin@a-pharma.cn', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. 演示公司 B（仅 GEO）
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (2, 'B 药企（演示）', 'B-DEMO', 'SAAS', 'ENTERPRISE', 'ACTIVE', 100, 'admin@b-pharma.cn', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 4. A 药企管理员（用户名: a_admin  密码: pass123）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, two_factor_enabled, created_at, updated_at)
VALUES (2, 'a_admin', '$2a$10$7Jch2YNp3SLTl69yurfQ6efLHxf4O6pO/bbIgLHqvAbWFpfhkyHGu', 'A 药企管理员', 'admin@a-pharma.cn', 'ACTIVE', FALSE, TRUE, FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 5. B 药企管理员（用户名: b_admin  密码: pass123）
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, two_factor_enabled, created_at, updated_at)
VALUES (4, 'b_admin', '$2a$10$7Jch2YNp3SLTl69yurfQ6efLHxf4O6pO/bbIgLHqvAbWFpfhkyHGu', 'B 药企管理员', 'admin@b-pharma.cn', 'ACTIVE', FALSE, TRUE, FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 6. 公司绑定（平台超管跨租户 + 两家公司超管）
-- 注：主站只有平台超管 + 公司超管两种角色（V2.0.45/46/51 口径）
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (1, 1, 1, 'SUPER_ADMIN', 'ACTIVE', NOW()),
       (2, 2, 1, 'SUPER_ADMIN', 'ACTIVE', NOW()),
       (3, 1, 2, 'SUPER_ADMIN', 'ACTIVE', NOW()),
       (5, 2, 4, 'SUPER_ADMIN', 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 产品目录（V2.0.54 主人拍板：展示 4 产品——棱镜/皓元/源策/医呈；
-- DINFO/PORM 置 DEPRECATED 软下架；MEDPPT=医呈-智能医学PPT生成智能体 新增）
-- ON CONFLICT 只保护全新初始化；存量库口径靠下方 UPDATE 强制对齐
-- ═══════════════════════════════════════════════════════════
INSERT INTO product (id, code, name, description, icon, sort_order, status, created_at, updated_at)
VALUES
  (1, 'GEO', '棱镜-智能GEO监测', 'AI 品牌监测与内容优化，监测 8 大 AI 平台', 'G', 1, 'ACTIVE', NOW(), NOW()),
  (2, 'HPD', '皓元-智能医院潜力预测', '医院销售潜力模型预测，WMAPE 6.6%', 'H', 2, 'ACTIVE', NOW(), NOW()),
  (3, 'AIDD', '源策-医药研发信息智能体', 'AI 辅助药物研发立项 Copilot（双种子方向：肿瘤 + 自免），含 CDE 中国监管数据 + 商业测算（PDB/米内）双增强', 'A', 3, 'ACTIVE', NOW(), NOW()),
  (4, 'DINFO', '辰录 · 智能信息填报系统', 'Dinfo 智能信息填报系统，LLM 智能填写 + 多任务架构 + 飞书 OAuth', 'D', 4, 'DEPRECATED', NOW(), NOW()),
  (5, 'PORM', '明枢-PORM 智能协作平台', 'POR-M 透明 × 协作中枢（看得见的协作），每张表/每条工单/每次审批可被看见、可被追溯', '明', 5, 'DEPRECATED', NOW(), NOW()),
  (6, 'MEDPPT', '医呈-智能医学PPT生成智能体', '智能医学PPT生成智能体，一键生成专业医学演示文稿', 'M', 6, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 存量库对齐 V2.0.55 口径（幂等：无论是否新库都强制置位；V2.0.55 = 产品对外名称更新）
UPDATE product SET name = '源策-医药研发信息智能体', updated_at = NOW() WHERE id = 3 AND name <> '源策-医药研发信息智能体';
UPDATE product SET name = '医呈-智能医学PPT生成智能体', updated_at = NOW() WHERE id = 6 AND name <> '医呈-智能医学PPT生成智能体';
UPDATE product SET status = 'DEPRECATED', updated_at = NOW() WHERE id IN (4, 5) AND status <> 'DEPRECATED';
UPDATE product SET status = 'ACTIVE', updated_at = NOW() WHERE id = 6 AND status <> 'ACTIVE';

-- ═══════════════════════════════════════════════════════════
-- 公司产品授权（V2.0.52 口径：A 药企仅 GEO；B 药企仅 GEO）
-- ═══════════════════════════════════════════════════════════
INSERT INTO company_product (id, company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
VALUES
  (1, 1, 1, NOW(), NOW() + INTERVAL '1 year', 50, 'ACTIVE', NOW(), NOW()),
  (6, 2, 1, NOW(), NOW() + INTERVAL '1 year', 50, 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 用户产品授权（a_admin 仅 GEO；b_admin 仅 GEO）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (1, 1, 1, 2, 2, 'ACTIVE', NOW()),
  (14, 2, 1, 4, 4, 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;

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
  (14, 5, 'OPERATOR', 'PORM 操作员', '["read", "task"]', 2, NOW()),
  (15, 6, 'SUPER_ADMIN', '医呈 超级管理员', '["*"]', 1, NOW()),
  (16, 6, 'OPERATOR', '医呈 操作员', '["read", "generate"]', 2, NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 产品内用户角色分配（a_admin 仅 GEO SUPER_ADMIN）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_role (id, company_id, product_id, user_id, role_code, assigned_by, created_at)
VALUES
  (1, 1, 1, 2, 'SUPER_ADMIN', 2, NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 子任务配置（V2.0.54：4 展示产品 ACTIVE；DINFO/PORM 随产品软下架 COMING_SOON；
-- MEDPPT 医呈未部署 COMING_SOON）
-- base_url / health_url 优先读 application.yml 的 lingyao.subtask.routes.<code>
-- ═══════════════════════════════════════════════════════════
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'ACTIVE', 'GEO 品牌监测核心引擎（已对接 OEG 子任务）· SSO 接入凌瑶主站', 'http://localhost:5180/#/auth/lingyao/sso-callback', 'http://127.0.0.1:8090/api/health', NOW(), NOW()),
  (2, 2, 'HPD 医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'ACTIVE', 'HPD 智能医院潜力预测（指向 MPD-myself 独立站 http://localhost:3100）', 'http://localhost:3100/#/sso/callback', 'http://localhost:3100/api/health', NOW(), NOW()),
  (3, 3, 'AIDD 研发子任务', 'aidd-engine', '/subtask/aidd', '/api/sub/aidd', 'ACTIVE', 'AI 立项 Copilot 双种子方向（肿瘤 + 自免）· SSO 已对接', 'http://localhost:13000/sso-callback.html', 'http://localhost:18080/api/_diag/version', NOW(), NOW()),
  (4, 4, '辰录 DINFO 填报子任务', 'dinfo-fill', '/subtask/dinfo', '/api/sub/dinfo', 'COMING_SOON', 'Dinfo 智能信息填报系统 · SSO 接入凌瑶主站', 'http://localhost:5181/auth/lingyao/callback', 'http://localhost:8281/api/_diag/version', NOW(), NOW()),
  (5, 5, '明枢 PORM 协作子任务', 'porm-collab', '/subtask/porm', '/api/sub/porm', 'COMING_SOON', 'POR-M 明枢智能协作平台 · SSO 接入凌瑶主站', 'http://localhost:8280/api/sso/callback-redirect', 'http://localhost:8280/api/_diag/version', NOW(), NOW()),
  (6, 6, '医呈 医学PPT子任务', 'medppt-gen', '/subtask/medppt', '/api/sub/medppt', 'COMING_SOON', '医呈 · 智能医学PPT生成智能体（MED-PPT 平台部署后置 ACTIVE 上线）', '', '', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 存量库子任务状态对齐（幂等）
UPDATE sub_task SET status = 'COMING_SOON', updated_at = NOW() WHERE id IN (4, 5) AND status <> 'COMING_SOON';

-- ═══════════════════════════════════════════════════════════
-- 默认推送通道配置（3 个占位通道；EMAIL 报名通道 dev 惯例 id=1000 在下方单独种）
-- ═══════════════════════════════════════════════════════════
INSERT INTO notification_channel (id, channel_type, name, webhook_url, status, sort_order, created_at, updated_at)
VALUES
  (1, 'FEISHU', '飞书群机器人（默认）', 'https://open.feishu.cn/open-apis/bot/v2/hook/REPLACE_ME', 'INACTIVE', 1, NOW(), NOW()),
  (2, 'WECHAT_WORK', '企微群机器人（默认）', 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=REPLACE_ME', 'INACTIVE', 2, NOW(), NOW()),
  (3, 'WECHAT_MP', '微信公众号模板消息', '', 'INACTIVE', 3, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- dev 常用测试数据（此前 H2 每次重启后要手工重建，现持久化免去 SOP）
-- ═══════════════════════════════════════════════════════════

-- EMAIL 报名通知通道（id=1000，与历史 SOP 一致）
INSERT INTO notification_channel (id, channel_type, name, webhook_url, status, sort_order, created_at, updated_at)
VALUES (1000, 'EMAIL', '邮件通知（报名推送）', '188407408@qq.com', 'ACTIVE', 10, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- GEOM 窗口联调用：凌瑶自营（私有化）公司 + pc_admin + GEOM SSO 回执口径
-- 用户名: pc_admin  密码: PrivateCust#2026
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, two_factor_enabled, created_at, updated_at)
VALUES (5, 'pc_admin', '$2b$10$27vcW4jQdCwQavTCLPXQluzuupaIJ1RAXUzCGjhtcBVR/pBxti0Pm', '私有化自营超管', 'pc@lingyao.cn', 'ACTIVE', FALSE, TRUE, FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (1000, '凌瑶自营（私有化）', 'PRIVATE-CUSTOMER', 'PRIVATE', 'ENTERPRISE', 'ACTIVE', 100, 'pc@lingyao.cn', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (1000, 1000, 5, 'SUPER_ADMIN', 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;

-- 凌瑶自营开通全部产品（V2.0.54：6 产品目录；MEDPPT 含其中，子任务未部署显示待部署）
-- 注意：不用显式 id——id=1005 已被 V2.0.53 持久化测试占用（A 药企 HPD SUSPENDED 软删留痕），
-- ON CONFLICT (id) DO NOTHING 会静默跳过导致医呈授权缺失。改按唯一约束 (company_id, product_id) upsert
INSERT INTO company_product (company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
SELECT 1000, p.id, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()
FROM product p WHERE p.id IN (1,2,3,4,5,6)
ON CONFLICT (company_id, product_id) DO UPDATE
  SET status = 'ACTIVE', updated_at = NOW();

-- pc_admin 全产品个人授权
INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (1000, 1000, 1, 5, 5, 'ACTIVE', NOW()),
  (1001, 1000, 2, 5, 5, 'ACTIVE', NOW()),
  (1002, 1000, 3, 5, 5, 'ACTIVE', NOW()),
  (1003, 1000, 4, 5, 5, 'ACTIVE', NOW()),
  (1004, 1000, 5, 5, 5, 'ACTIVE', NOW()),
  (1005, 1000, 6, 5, 5, 'ACTIVE', NOW())
ON CONFLICT (id) DO NOTHING;

-- FEISHU SSO 配置（dev 测试凭据；生产/staging 需真实凭据在 SSO 配置界面重配）
INSERT INTO company_sso_config (id, company_id, provider, enabled, app_id, app_secret_enc, redirect_uri, base_url, scope, created_by, created_at, updated_at)
VALUES (1, 1000, 'FEISHU', TRUE, 'cli_dev_test_app_id', 'cli_dev_test_secret', 'http://localhost:9091/api/sso/feishu/callback', 'https://open.feishu.cn/open-apis', 'contact:user.base:readonly', 5, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- IDENTITY 序列偏移（对齐 dev data.sql 的 Bug-22 修复：
-- 自增从 1000 起，避开种子显式 id 1~15/1000+ 区间）
-- PG 写法：setval(seq, max_id 已含种子 1004/1000 等，直接对齐最大值)
-- 注意：种子执行时 Hibernate 建表已创建各表序列，此处用 setval 幂等对齐
-- ═══════════════════════════════════════════════════════════
SELECT setval(pg_get_serial_sequence('sys_user', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM sys_user), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('company', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM company), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('company_user', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM company_user), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('product', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM product), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('company_product', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM company_product), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('product_role', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM product_role), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('product_user_grant', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM product_user_grant), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('product_user_role', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM product_user_role), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('notification_channel', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM notification_channel), 999) + 1, false);
SELECT setval(pg_get_serial_sequence('company_sso_config', 'id'), GREATEST((SELECT COALESCE(MAX(id),1) FROM company_sso_config), 999) + 1, false);
