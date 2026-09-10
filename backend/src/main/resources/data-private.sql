-- ═══════════════════════════════════════════════════════════
-- 凌瑶智数 · 私有化部署初始化数据
--
-- 与 data.sql（SAAS 多公司演示数据）的区别：
-- - 只有 1 家公司（私有化客户自己）
-- - 只有 1 个超管账号（admin/admin123，强制登录后改密）
-- - 私有化公司默认开通全部「已部署」产品（V2.0.57 部署就绪口径：
--   GEO/HPD/AIDD；医呈未部署不默认开，部署后在后台手动开通）
-- - 所有 sub_task 状态为 REGISTERED（由客户后续按合同激活）
--
-- 与 application-private.yml 联动：
-- - lingyao.deployment.mode=PRIVATE
-- - lingyao.deployment.tenant-mode=SINGLE
-- - lingyao.deployment.registration=CLOSED
-- ═══════════════════════════════════════════════════════════

-- 1. 私有化超管账号（默认密码 admin123，首次登录强制改密）
-- BCrypt hash of "admin123" — 与 data.sql admin 同密码保持一致
-- V2.0.77.5（主人 2026-09-07 拍板）：admin email 改 188407408@qq.com（主人期望邮箱）
-- password_changed=FALSE 触发首登强制改密
INSERT INTO sys_user (id, username, password_hash, display_name, email, status, is_platform_admin, password_changed, two_factor_enabled, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$sGGMda/K1XOkDq/gg5B18.Xu99jzot/vt/Et6k6KUi5NVa1hKM6hK', '私有化超管', '188407408@qq.com', 'ACTIVE', TRUE, FALSE, FALSE, NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 2. 私有化客户公司（只有 1 家）
-- deployment_mode=PRIVATE 标识这是私有化租户
INSERT INTO company (id, name, code, deployment_mode, license_plan, status, max_users, contact_email, created_at, updated_at)
VALUES (1, '私有化客户', 'PRIVATE-CUSTOMER', 'PRIVATE', 'ENTERPRISE', 'ACTIVE', 100, 'admin@private.local', NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 3. 超管绑定到私有化公司
INSERT INTO company_user (id, company_id, user_id, role, status, created_at)
VALUES (1, 1, 1, 'SUPER_ADMIN', 'ACTIVE', NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 产品目录（V2.0.58 对齐主站 6 产品口径：4 展示 + 2 软下架）
-- 棱镜(GEO)/皓元(HPD)/源策(AIDD)/医呈(MEDPPT) ACTIVE；
-- 辰录(DINFO)/明枢(PORM) DEPRECATED 软下架（鉴权/历史数据保留可恢复）
-- 存量库靠下方 UPDATE 强制对齐（幂等）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product (id, code, name, description, icon, sort_order, status, created_at, updated_at)
VALUES
  (1, 'GEO', '棱镜-智能GEO监测', 'AI 品牌监测与内容优化，监测 8 大 AI 平台', 'G', 1, 'ACTIVE', NOW(), NOW()),
  (2, 'HPD', '皓元-智能医院潜力预测', '医院销售潜力模型预测，WMAPE 6.6%', 'H', 2, 'ACTIVE', NOW(), NOW()),
  (3, 'AIDD', '源策-医药研发信息智能体', 'AI 辅助药物研发立项 Copilot（双种子方向：肿瘤 + 自免），含 CDE 中国监管数据 + 商业测算（PDB/米内）双增强', 'A', 3, 'ACTIVE', NOW(), NOW()),
  (4, 'DINFO', '辰录 · 智能信息填报系统', 'Dinfo 智能信息填报系统，LLM 智能填写 + 多任务架构 + 飞书 OAuth', 'D', 4, 'DEPRECATED', NOW(), NOW()),
  (5, 'PORM', '明枢-PORM 智能协作平台', 'POR-M 透明 × 协作中枢（看得见的协作），每张表/每条工单/每次审批可被看见、可被追溯', '明', 5, 'DEPRECATED', NOW(), NOW()),
  (6, 'MEDPPT', '医呈-智能医学PPT生成智能体', '智能医学PPT生成智能体，一键生成专业医学演示文稿', 'M', 6, 'ACTIVE', NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 存量库对齐最新口径（幂等：新库 INSERT 已正确则零行受影响）
UPDATE product SET name = '源策-医药研发信息智能体', description = 'AI 辅助药物研发立项 Copilot（双种子方向：肿瘤 + 自免），含 CDE 中国监管数据 + 商业测算（PDB/米内）双增强', updated_at = NOW() WHERE id = 3 AND name <> '源策-医药研发信息智能体';
UPDATE product SET name = '医呈-智能医学PPT生成智能体', updated_at = NOW() WHERE id = 6 AND name <> '医呈-智能医学PPT生成智能体';
UPDATE product SET status = 'ACTIVE', updated_at = NOW() WHERE id IN (1, 2, 3, 6) AND status <> 'ACTIVE';
UPDATE product SET status = 'DEPRECATED', updated_at = NOW() WHERE id IN (4, 5) AND status <> 'DEPRECATED';
-- 旧私有化种子的 id=4 曾是 POR（飞轮）：如有残留旧行则软下架处理，不物理删（保历史外键）
UPDATE product SET code = 'PORM', name = '明枢-PORM 智能协作平台', status = 'DEPRECATED', updated_at = NOW()
  WHERE id = 5 AND code = 'POR';
UPDATE product SET status = 'DEPRECATED', updated_at = NOW() WHERE code = 'POR' AND status <> 'DEPRECATED';

-- ═══════════════════════════════════════════════════════════
-- 私有化客户默认开通全部「已部署」产品（V2.0.57 部署就绪口径）
-- 医呈（MEDPPT）子任务 COMING_SOON 未部署：开通了也进不去 → 不默认授权，
-- 待 MED-PPT 平台部署置 ACTIVE 后在管理后台手动开通
-- （按唯一约束 upsert，不写显式 id，避免 ON CONFLICT (id) 静默跳过）
-- ═══════════════════════════════════════════════════════════
INSERT INTO company_product (company_id, product_id, license_start, license_end, max_users, status, created_at, updated_at)
SELECT 1, p.id, NOW(), NOW() + INTERVAL '10 years', 100, 'ACTIVE', NOW(), NOW()
FROM product p WHERE p.code IN ('GEO', 'HPD', 'AIDD')
ON CONFLICT (company_id, product_id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 超管获得已部署产品的访问授权（同部署就绪口径）
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_grant (company_id, product_id, user_id, granted_by, status, created_at)
SELECT 1, p.id, 1, 1, 'ACTIVE', NOW()
FROM product p WHERE p.code IN ('GEO', 'HPD', 'AIDD')
ON CONFLICT (company_id, product_id, user_id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 产品内默认角色（V2.0.58 对齐 6 产品；医呈补 SUPER_ADMIN/OPERATOR）
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
  (16, 6, 'OPERATOR', '医呈 操作员', '["read", "generate"]', 2, NOW()) ON CONFLICT (id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 超管在已部署产品内都是 SUPER_ADMIN
-- ═══════════════════════════════════════════════════════════
INSERT INTO product_user_role (company_id, product_id, user_id, role_code, assigned_by, created_at)
SELECT 1, p.id, 1, 'SUPER_ADMIN', 1, NOW()
FROM product p WHERE p.code IN ('GEO', 'HPD', 'AIDD')
ON CONFLICT (company_id, product_id, user_id) DO NOTHING;

-- ═══════════════════════════════════════════════════════════
-- 子任务配置（V2.0.58 对齐 6 产品；私有化默认 REGISTERED/COMING_SOON 待激活）
-- base_url 和 health_url 留 NULL，由客户运维按合同配置
-- 激活方法：在 admin 后台修改 status=ACTIVE 并填 base_url
-- ═══════════════════════════════════════════════════════════
INSERT INTO sub_task (id, product_id, task_name, task_code, entry_path, api_prefix, status, description, base_url, health_url, created_at, updated_at)
VALUES
  (1, 1, 'GEO 监测子任务', 'geo-monitor', '/subtask/geo', '/api/sub/geo', 'REGISTERED', 'GEO 品牌监测核心引擎（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (2, 2, 'HPD 医院潜力预测子任务', 'hpd-predictor', '/subtask/hpd', '/api/sub/hpd', 'REGISTERED', 'HPD 智能医院潜力预测（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (3, 3, 'AIDD 研发子任务', 'aidd-engine', '/subtask/aidd', '/api/sub/aidd', 'REGISTERED', '源策-医药研发信息智能体引擎（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (4, 4, '辰录 DINFO 填报子任务', 'dinfo-fill', '/subtask/dinfo', '/api/sub/dinfo', 'COMING_SOON', 'Dinfo 智能信息填报系统（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (5, 5, '明枢 PORM 协作子任务', 'porm-collab', '/subtask/porm', '/api/sub/porm', 'COMING_SOON', 'POR-M 明枢智能协作平台（私有化待激活）', NULL, NULL, NOW(), NOW()),
  (6, 6, '医呈 医学PPT子任务', 'medppt-gen', '/subtask/medppt', '/api/sub/medppt', 'COMING_SOON', '医呈 · 智能医学PPT生成智能体（MED-PPT 平台部署后置 ACTIVE 上线）', NULL, NULL, NOW(), NOW()) ON CONFLICT (id) DO NOTHING;

-- 存量库对齐：旧私有化种子的子任务 id=4 曾挂 product_id=4(POR) por-agent → 对齐为 porm-collab（product_id=5）
UPDATE sub_task SET product_id = 5, task_name = '明枢 PORM 协作子任务', task_code = 'porm-collab',
  entry_path = '/subtask/porm', api_prefix = '/api/sub/porm', status = 'COMING_SOON', updated_at = NOW()
  WHERE id = 5 AND task_code <> 'porm-collab';

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