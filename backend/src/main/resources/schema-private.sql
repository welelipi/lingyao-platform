-- ══════════════════════════════════════════════════════════════════
-- 凌瑶智数 · 私有化部署 · Schema 升级脚本（V2.0.10+）
--
-- 启动时由 application-private.yml 的 schema-locations 引用
-- (continue-on-error=true 已开，重复执行不会报错)
--
-- V2.0.10 升级内容：
-- 1) CompanyStatus 枚举从 3 种 (ACTIVE/SUSPENDED/DELETED) 扩到 5 种
--    (PENDING/ACTIVE/EXPIRED/SUSPENDED/DELETED)
-- 2) Hibernate ddl-auto=update 不更新 CHECK 约束，必须手动 ALTER
-- 3) 创建 license_reminder_log 表（V2.0.10 新增，用于过期提醒去重）
-- ══════════════════════════════════════════════════════════════════

-- ── 1) 升级 CompanyStatus CHECK 约束 ──────────
-- 老约束名是 Hibernate 生成的：company_status_check
-- 用 IF EXISTS 保护重跑，IF NOT EXISTS 不支持 PostgreSQL 9.5+
ALTER TABLE company DROP CONSTRAINT IF EXISTS company_status_check;
ALTER TABLE company ADD CONSTRAINT company_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'SUSPENDED', 'DELETED'));

-- ── 2) 创建 license_reminder_log 表 ──────────
-- V2.0.10 修复：字段名 daysBefore → noticeDays（Spring Data JPA 解析方法名歧义）
-- 老 jar 启动失败时 Hibernate 已经建过 days_before 列，需 RENAME 迁移
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name='license_reminder_log' AND column_name='days_before'
    ) THEN
        ALTER TABLE license_reminder_log RENAME COLUMN days_before TO notice_days;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS license_reminder_log (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    reminder_type   VARCHAR(16) NOT NULL,    -- EXPIRING / EXPIRED
    notice_days     INT,                     -- 提前天数（30/15/7/1，EXPIRED 为 NULL）
    sent_to         TEXT,                    -- 接收人列表（邮箱/企微 id 逗号分隔）
    channel         VARCHAR(32),             -- WECHAT_WORK / EMAIL / IN_APP
    sent_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    success         BOOLEAN NOT NULL DEFAULT TRUE,
    error_message   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_license_reminder_company
    ON license_reminder_log(company_id);
CREATE INDEX IF NOT EXISTS idx_license_reminder_type
    ON license_reminder_log(reminder_type);
CREATE INDEX IF NOT EXISTS idx_license_reminder_sent_at
    ON license_reminder_log(sent_at);

-- ── 3) 通知表加 license 类型（如果还没有）──
-- 复用现有 NotificationChannel.ChannelType 枚举，新增 LICENSE 值
-- 注意：Hibernate @Enumerated(STRING) + ddl-auto=update 不改列类型，无需 ALTER
-- 业务侧 PushService 推送到 WECHAT_WORK / EMAIL 通道即可

-- ══════════════════════════════════════════════════════════════════
-- V2.0.19+ SSO 配置模块（主平台统一 SSO 入口）
--
-- 设计要点：
-- 1) company_sso_config 粒度 = 公司 × Provider（一家公司可配多家）
-- 2) app_secret_enc 用 KMS 加密存储（Hibernate ddl-auto=update 不处理加密，
--    业务层 SsoConfigService.encryptSecret() / decryptSecret() 加解密）
-- 3) dept_mapping / extra_config 用 JSON 字符串存（H2/PG 都支持 TEXT）
-- 4) sso_login_audit 不挂 tenantFilter，按公司 ID 直接查询
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS company_sso_config (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    provider        VARCHAR(32) NOT NULL,           -- FEISHU/DINGTALK/WECOM/GENERIC_OIDC/SAML
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    app_id          VARCHAR(256),                   -- Feishu app_id / Dingtalk app_key / Wecom corp_id / OIDC client_id
    app_secret_enc  TEXT,                            -- KMS 加密的 app_secret / client_secret
    redirect_uri    VARCHAR(512),                   -- 主平台 callback URL
    base_url        VARCHAR(256),                   -- Feishu:open.feishu.cn / OIDC:issuer URL
    scope           VARCHAR(256),                   -- OAuth scope
    dept_mapping    TEXT,                            -- JSON: 飞书部门 ID → BU 映射规则
    extra_config    TEXT,                            -- JSON: SAML metadata / 企业特殊字段
    created_by      BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_company_sso_provider UNIQUE (company_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_sso_company ON company_sso_config(company_id);
CREATE INDEX IF NOT EXISTS idx_sso_provider ON company_sso_config(provider);

CREATE TABLE IF NOT EXISTS sso_login_audit (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT,                      -- 可空（公司未识别）
    provider            VARCHAR(32),                  -- FEISHU/DINGTALK/WECOM/GENERIC_OIDC/SAML
    employee_id           VARCHAR(128),                  -- 主平台员工 ID
    provider_user_id    VARCHAR(256),                  -- 三方原始 ID
    display_name        VARCHAR(128),
    dept_id             VARCHAR(128),                  -- 飞书部门 ID / 钉钉 dept_id
    sub_product_code    VARCHAR(32),                  -- GEOM/MPDM/AIDD/DINFO/PORM/MED-PPT
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING/SUCCESS/FAILED
    client_ip           VARCHAR(64),
    user_agent          VARCHAR(512),
    error_message       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sso_audit_company ON sso_login_audit(company_id);
CREATE INDEX IF NOT EXISTS idx_sso_audit_provider ON sso_login_audit(provider);
CREATE INDEX IF NOT EXISTS idx_sso_audit_status ON sso_login_audit(status);
CREATE INDEX IF NOT EXISTS idx_sso_audit_created ON sso_login_audit(created_at);