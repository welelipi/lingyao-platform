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

-- ══════════════════════════════════════════════════════════════════
-- V2.0.19 W6 阶段 2.5 B1 — 子任务 / 产品授权集中审批表
--
-- 设计要点：
-- 1) 公司超管审批流：员工调用子任务 / 申请产品访问权限 / 申请生成专属链接 等
-- 2) type + payload_json 通用化：避免每种审批流单独建表
-- 3) status 单层：PENDING → APPROVED / REJECTED（不支持嵌套多级审批）
-- 4) Hibernate ddl-auto=update 在 H2 会自动建，PG 上手工建保证稳定
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS sub_task_approval (
    id                    BIGSERIAL PRIMARY KEY,
    company_id            BIGINT NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    product_id            BIGINT REFERENCES product(id) ON DELETE SET NULL,
    type                  VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    requester_id          BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    requester_username    VARCHAR(64) NOT NULL,
    reason                TEXT,
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    approver_id           BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    approver_username     VARCHAR(64),
    approve_time          TIMESTAMP,
    reject_reason         TEXT,
    payload_json          TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT chk_approval_type CHECK (type IN ('SUBTASK_INVOKE','PRODUCT_GRANT','SSO_LINK_REQUEST','OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_approval_company_status
    ON sub_task_approval(company_id, status);
CREATE INDEX IF NOT EXISTS idx_approval_product
    ON sub_task_approval(product_id);
CREATE INDEX IF NOT EXISTS idx_approval_type
    ON sub_task_approval(type);
CREATE INDEX IF NOT EXISTS idx_approval_requester
    ON sub_task_approval(requester_id);
CREATE INDEX IF NOT EXISTS idx_approval_created_at
    ON sub_task_approval(created_at DESC);
-- ══════════════════════════════════════════════════════════════════
-- V2.0.20 P1 阶段 A — JWT 黑名单表
--
-- 用途：
-- 1) logout 后将当前 jti 写入黑名单，JwtAuthFilter 校验时拒绝
-- 2) 密码修改 / 管理员撤销会话时整批写入
-- 3) expires_at = JWT 原始 exp（过期后无需清理，依靠定时清理或自然过期）
--
-- 设计要点：
-- - jti 唯一约束（JWT ID = UUID，防止重复黑名单）
-- - 复合索引 (user_id, expires_at) 加快用户级查询
-- - 暂不引入定期清理任务，黑名单表自然累积（V2.0.21+ 补）
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS token_blacklist (
    id                    BIGSERIAL PRIMARY KEY,
    jti                   VARCHAR(64) NOT NULL UNIQUE,
    user_id               BIGINT NOT NULL,
    company_id            BIGINT,
    expires_at            TIMESTAMP NOT NULL,
    reason                VARCHAR(32) NOT NULL DEFAULT 'LOGOUT',
    ip_address            VARCHAR(64),
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_blacklist_reason CHECK (reason IN ('LOGOUT','ADMIN_REVOKE','PASSWORD_CHANGED','REFRESH_ROTATE','OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_blacklist_user_expires
    ON token_blacklist(user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_blacklist_expires
    ON token_blacklist(expires_at);

-- ══════════════════════════════════════════════════════════════════
-- V2.0.21 P1.4 阶段 B — refresh_token 滑动续期表
--
-- 用途：
-- 1) 登录时颁发 refresh_token（30 天），存 SHA-256 hash
-- 2) 每次 refresh 滑动一次：旧 used_at + replaced_by_id 指向新 token
-- 3) 防重放：已 used 的 token 再次出现 → 整条链全部撤销
-- 4) 用户 logout / 改密 / 管理员撤销 → revoke_at 标时间 + revoke_reason
--
-- 设计要点：
-- - token_hash 唯一（hash 撞库风险可忽略）
-- - 复合索引 (user_id, used_at, expires_at) 加快按用户查 active
-- - 不存明文 token（数据库泄露也不可重放）
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS refresh_token (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    token_hash        VARCHAR(64) NOT NULL UNIQUE,
    expires_at        TIMESTAMP NOT NULL,
    used_at           TIMESTAMP,
    replaced_by_id    BIGINT,
    revoked_at        TIMESTAMP,
    revoked_reason    VARCHAR(32),
    ip_address        VARCHAR(64),
    user_agent        VARCHAR(256),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_refresh_revoked_reason CHECK (revoked_reason IS NULL OR revoked_reason IN
        ('LOGOUT','PASSWORD_CHANGED','ADMIN_REVOKE','REFRESH_REPLAY_DETECTED','OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_refresh_user_active
    ON refresh_token(user_id, used_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_expires
    ON refresh_token(expires_at);

-- ══════════════════════════════════════════════════════════════════
-- V2.0.21 P1.5 阶段 B — password_reset_token 邮件重置密码表
--
-- 用途：
-- 1) forgot-password → 生成 token + 发邮件
-- 2) reset-password 校验 token → 重置密码 + 撤销 refresh_token
--
-- 设计要点：
-- - token_hash 唯一 + 存 SHA-256（明文仅邮件一次传输）
-- - 一次性 token（used_at 标记）
-- - 短 TTL（默认 1 小时，邮件场景够用）
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS password_reset_token (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    token_hash        VARCHAR(64) NOT NULL UNIQUE,
    expires_at        TIMESTAMP NOT NULL,
    used_at           TIMESTAMP,
    ip_address        VARCHAR(64),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reset_user_active
    ON password_reset_token(user_id, used_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_reset_expires
    ON password_reset_token(expires_at);

-- ══════════════════════════════════════════════════════════════════
-- V2.0.26 P2.4 阶段 A — auth_audit_event 安全审计表
--
-- 用途：
-- 记录认证安全事件（独立于 company_audit_log 业务审计），用于：
-- 1) 安全监控 dashboard（按事件类型统计 + 趋势）
-- 2) 异常行为排查（同一 IP 多次失败 / 同一用户多次登出后复用 token）
-- 3) 合规审计（谁什么时候从哪个 IP 尝试登录 / 改密 / 被限流）
--
-- 设计要点：
-- - 必填：event_type + created_at（其余可空，因为登录失败 / CSRF 拒绝时拿不到真实 user）
-- - 索引聚焦三类查询：① 事件类型 + 时间 ② 用户 + 时间 ③ IP + 时间
-- - metadata 用 TEXT 存 JSON 字符串（事件特有数据）
-- - 默认保留 90 天（V2.0.27 加归档清理 job）
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS auth_audit_event (
    id                BIGSERIAL PRIMARY KEY,
    event_type        VARCHAR(48) NOT NULL,
    user_id           BIGINT,
    username          VARCHAR(64),
    company_id        BIGINT,
    ip_address        VARCHAR(64),
    user_agent        VARCHAR(512),
    request_path      VARCHAR(256),
    request_method    VARCHAR(8),
    success           BOOLEAN NOT NULL DEFAULT TRUE,
    error_code        VARCHAR(64),
    error_message     VARCHAR(512),
    metadata          TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_audit_type_created
    ON auth_audit_event(event_type, created_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_user_created
    ON auth_audit_event(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_ip_created
    ON auth_audit_event(ip_address, created_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_created
    ON auth_audit_event(created_at);
CREATE INDEX IF NOT EXISTS idx_auth_audit_company
    ON auth_audit_event(company_id);

-- ══════════════════════════════════════════════════════════════════
-- V2.0.26 P2.4 阶段 D — user_2fa 双因素认证表（2FA 预留 SKELETON）
--
-- ⚠️ V2.0.26 仅建表 + 占位，未实际启用 2FA。V2.0.27+ 落地 TOTP + QR + 备份码。
--
-- 字段说明：
-- - secret_encrypted: AES-256-GCM 加密的 TOTP secret（Base64 IV+ciphertext+tag）
-- - backup_codes_hash: 8 个一次性 backup code 的 SHA-256 哈希（JSON 数组）
-- - enabled: 是否启用（用户首次验证 TOTP 后才置 true）
-- - verified_at: 用户首次验证 2FA 的时间
-- - last_used_at: 上次 TOTP 验证时间（防重放窗口）
--
-- 索引：
-- - user_id UNIQUE：一个用户只允许一份 2FA 记录
-- ══════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS user_2fa (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT NOT NULL UNIQUE,
    secret_encrypted  TEXT,
    backup_codes_hash TEXT,
    enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at       TIMESTAMP,
    last_used_at      TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_2fa_enabled
    ON user_2fa(enabled);

-- ══════════════════════════════════════════════════════════════════
-- V2.0.26 P2.4 阶段 D — sys_user 加 two_factor_enabled 字段
--
-- ALTER TABLE 加字段（H2 + PG 都支持），V2.0.26 ddl-auto=update 会自动加，
-- 但写在这里确保 schema-private.sql 启动时也加一次（dev profile 用）
-- ══════════════════════════════════════════════════════════════════

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE;
