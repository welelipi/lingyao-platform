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