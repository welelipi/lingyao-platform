-- ═══════════════════════════════════════════════════════════
-- 凌瑶智数 · 私有化部署初始化数据
--
-- 与 data.sql（SAAS 多公司演示数据）的区别：
-- - 只有 1 家公司（私有化客户自己）
-- - 只有 1 个超管账号（admin/admin123，强制登录后改密）
-- - 私有化公司默认获得全部 4 个产品的授权
-- - 所有 sub_task 状态为 REGISTERED（由客户后续按合同激活）
--
-- 与 application-private.yml 联动：
-- - lingyao.deployment.mode=PRIVATE
-- - lingyao.deployment.tenant-mode=SINGLE
-- - lingyao.deployment.registration=CLOSED
-- ═══════════════════════════════════════════════════════════

-- 1. 私有化超管账号（默认密码 admin123，首次登录强制改密）
-- BCrypt hash of "admin123" — 与 data.sql admin 同密码保持一致
-- password_changed=FALSE 触发首登强制改密
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$sGGMda/K1XOkDq/gg5B18.Xu99jzot/vt/Et6k6KUi5NVa1hKM6hK', '私有化超管', 'admin@private.local', 'ACTIVE', TRUE, FALSE, NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 2. 私有化客户公司（只有 1 家）
-- deployment_mode=PRIVATE 标识这是私有化租户
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (1, '私有化客户', 'PRIVATE-CUSTOMER', 'PRIVATE', 'ENTERPRISE', 'ACTIVE', 100, 'admin@private.local', NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 3. 超管绑定到私有化公司
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (1, 1, 1, 'SUPER_ADMIN', 'ACTIVE', NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 产品目录（私有化客户默认看到全部 4 个产品）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product (id, code, name, description, icon, sort_order, status, created_at, updated_at)
VALUES
  (1, 'GEO', 'GEO 智策', 'AI 品牌监测与内容优化，监测 8 大 AI 平台', 'G', 1, 'ACTIVE', NOW(), NOW()),
  (2, 'HPD', '医院潜力预测', '医院销售潜力模型预测，WMAPE 6.6%', 'H', 2, 'ACTIVE', NOW(), NOW()),
  (3, 'AIDD', 'AIDD 研发反馈', 'AI 辅助药物研发信息反馈系统', 'A', 3, 'ACTIVE', NOW(), NOW()),
  (4, 'POR', '药企协作辅助智能体', '让药企内部效率提升 300%', 'P', 4, 'ACTIVE', NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 私有化客户获得全部 4 个产品的默认授权
-- 客户后续可在后台 admin 控制台关闭不需要的
-- ═══════════════════════════════════════════════════════════
INSERT INTO company_product (id, company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
VALUES
  (1, 1, 1, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()),  -- GEO
  (2, 1, 2, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()),  -- HPD
  (3, 1, 3, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()),  -- AIDD
  (4, 1, 4, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()) ON CONFLICT (id) DO NOTHING;  -- POR

-- ═══════════════════════════════════════════════════════════
-- 超管获得全部产品的访问授权
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_grant (id, company_id, product_id, user_id, granted_by, status, created_at)
VALUES
  (1, 1, 1, 1, 1, 'ACTIVE', NOW()),  -- admin → GEO
  (2, 1, 2, 1, 1, 'ACTIVE', NOW()),  -- admin → HPD
  (3, 1, 3, 1, 1, 'ACTIVE', NOW()),  -- admin → AIDD
  (4, 1, 4, 1, 1, 'ACTIVE', NOW()) ON CONFLICT (id) DO NOTHING;  -- admin → POR

-- ═══════════════════════════════════════════════════════════
-- 产品内默认角色（每个产品 2 个：超管 + 操作员）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_role (id, product_id, role_code, role_name, permissions, sort_order, created_at)
VALUES
  (1, 1, 'SUPER_ADMIN', 'GEO 超级管理员', '["*"]', 1, NOW()),
  (2, 1, 'OPERATOR', 'GEO 操作员', '["read", "write"]', 2, NOW()),
  (3, 2, 'SUPER_ADMIN', 'HPD 超级管理员', '["*"]', 1, NOW()),
  (4, 2, 'OPERATOR', 'HPD 操作员', '["read", "predict"]', 2, NOW()),
  (5, 3, 'SUPER_ADMIN', 'AIDD 超级管理员', '["*"]', 1, NOW()),
  (6, 3, 'OPERATOR', 'AIDD 操作员', '["read", "search"]', 2, NOW()),
  (7, 4, 'SUPER_ADMIN', 'POR 超级管理员', '["*"]', 1, NOW()),
  (8, 4, 'OPERATOR', 'POR 操作员', '["read", "task"]', 2, NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 超管在所有产品内都是 SUPER_ADMIN
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_role (id, company_id, product_id, user_id, role_code, assigned_by, created_at)
VALUES
  (1, 1, 1, 1, 'SUPER_ADMIN', 1, NOW()),  -- admin → GEO
  (2, 1, 2, 1, 'SUPER_ADMIN', 1, NOW()),  -- admin → HPD
  (3, 1, 3, 1, 'SUPER_ADMIN', 1, NOW()),  -- admin → AIDD
  (4, 1, 4, 1, 'SUPER_ADMIN', 1, NOW()) ON CONFLICT (id) DO NOTHING;  -- admin → POR

-- ═══════════════════════════════════════════════════════════
-- 子任务配置（私有化默认全部 REGISTERED）
-- base_url 和 health_url 留 NULL，由客户运维按合同配置
-- 激活方法：在 admin 后台修改 status=ACTIVE 并填 base_url
-- ═══════════════════════════════════════════════════════════
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'REGISTERED', 'GEO 品牌监测核心引擎（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (2, 2, '医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'REGISTERED', '医院销售潜力预测模型（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (3, 3, 'AIDD 研发子任务', 'aidd-engine', '/subtask/aidd', '/api/sub/aidd', 'REGISTERED', 'AI 辅助药物研发引擎（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (4, 4, '协作智能体子任务', 'por-agent', '/subtask/por', '/api/sub/por', 'REGISTERED', '药企协作智能体引擎（私有化待激活）', NULL, NULL, NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 默认推送通道（同 SAAS 配置，客户需替换 webhook URL）
-- ═══════════════════════════════════════════════════════════
INSERT INTO notification_channel (id, channel_type, name, webhook_url, status, sort_order, created_at, updated_at)
VALUES
  (1, 'FEISHU', '飞书群机器人（私有化默认）', 'https://open.feishu.cn/open-apis/bot/v2/hook/REPLACE_ME', 'INACTIVE', 1, NOW(), NOW()),
  (2, 'WECHAT_WORK', '企微群机器人（私有化默认）', 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=REPLACE_ME', 'INACTIVE', 2, NOW(), NOW()),
  (3, 'WECHAT_MP', '微信公众号模板消息', '', 'INACTIVE', 3, NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- ID 自增偏移（与 SAAS 演示数据完全隔离）
-- 私有化客户后续创建任何数据，id 从 1000 开始
-- 避免与 SAAS 平台的 id 段冲突
--
-- PostgreSQL 方言：Hibernate IDENTITY 列底层序列名 = <表名>_id_seq
-- 用 pg_get_serial_sequence 动态获取，避免硬编码序列名跟实际不匹配
-- 注意：company_audit_log 表**不重置自增**，因为：
-- 1. 该表无显式 id INSERT
-- 2. 重置到 1000 会与首次启动时登录产生的 audit_log(1000) 冲突
-- 3. PostgreSQL IDENTITY 模式天然持久化序列状态，重启后从 max(id)+1 继续
-- ═══════════════════════════════════════════════════════════
DO $$
DECLARE
  seq_name TEXT;
BEGIN
  FOR seq_name IN
    SELECT pg_get_serial_sequence(c.relname, a.attname)
    FROM pg_class c
    JOIN pg_attribute a ON a.attrelid = c.oid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname IN ('sys_user','company','company_user','product',
                        'company_product','product_role','product_user_grant',
                        'product_user_role','registration','notification_channel',
                        'sub_task','invitation')
      AND a.attname = 'id'
      AND a.attnum > 0
      AND NOT a.attisdropped
      AND c.relkind = 'r'
      AND n.nspname = current_schema()
  LOOP
    IF seq_name IS NOT NULL THEN
      EXECUTE format('ALTER SEQUENCE %I RESTART WITH 1000', seq_name);
    END IF;
  END LOOP;
END $$;