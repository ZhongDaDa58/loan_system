-- =============================================================================
-- V2：幂等键（阶段四 4.3）
-- =============================================================================
-- ⚠️ 为什么必须单独一条 V2，而不是改 V1：
--    V1 已经在开发库应用过（checksum 已记录在 flyway_schema_history），
--    改动它会导致校验失败（validate-on-migrate=true 会直接报错）。
--    已应用的迁移脚本**永远不能改**，只能追加新版本。
--
-- 背景：阶段四 4.3 需要"同一用户 + 同一 Idempotency-Key 不能重复建单"。
--       Redis 层是快速拦截，这里是数据库兜底 —— Redis 不可用/被绕过时仍能挡住。
--
-- 兼容性：
--   · 列可空 → 存量记录（及不带该请求头的老客户端）为 NULL
--   · MySQL 唯一索引允许多个 NULL → 这些记录不参与唯一性约束，不影响现有功能
-- =============================================================================

ALTER TABLE loan_application
    ADD COLUMN idempotency_key VARCHAR(64) DEFAULT NULL
        COMMENT '幂等键（来自请求头 Idempotency-Key；NULL = 客户端未提供）',
    ADD UNIQUE KEY uk_user_idem (user_id, idempotency_key);
