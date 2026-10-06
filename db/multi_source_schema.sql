-- ============================================================================
-- 多数据源融合 —— W2 预留表结构（幂等脚本，可重复执行）
-- 目标库：loan_risk_control
-- 说明：仅新增三张独立表，不改动任何现有表结构。
-- ============================================================================

-- 3.1 多源授权留痕表（合规：授权留痕、脱敏）
CREATE TABLE IF NOT EXISTS data_source_authorization (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        VARCHAR(64)  NOT NULL COMMENT '用户ID，关联 sys_user.user_id',
  source_type    VARCHAR(32)  NOT NULL COMMENT '数据源类型：credit/operator/social/device',
  auth_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING=待授权 AUTHORIZED=已授权 REVOKED=已撤销 EXPIRED=已过期',
  auth_token     VARCHAR(128) NULL COMMENT '模拟授权凭证（脱敏/加密存储，不落明文敏感信息）',
  scope_json     TEXT         NULL COMMENT '授权范围（JSON）',
  authorized_at  DATETIME     NULL COMMENT '授权时间',
  expiry_at      DATETIME     NULL COMMENT '授权有效期至',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_source (user_id, source_type),
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多数据源授权留痕表';

-- 3.2 多源标准化数据表（Provider 拉取结果落库；特征宽表化留待 W5）
CREATE TABLE IF NOT EXISTS user_multi_source_data (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        VARCHAR(64) NOT NULL COMMENT '用户ID',
  source_type    VARCHAR(32) NOT NULL COMMENT 'credit/operator/social/device',
  data_json      TEXT        NULL COMMENT '标准化数据载荷（JSON），字段口径见《多源数据.md》',
  data_version   VARCHAR(16) NULL COMMENT '数据口径版本（对齐模型/特征版本）',
  fetch_status   VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
                 COMMENT 'SUCCESS/FAILED/STALE=数据过期',
  fetched_at     DATETIME    NULL COMMENT '最近拉取时间',
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_user_source (user_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多源标准化数据表';

-- 3.3 数据源 Provider 注册与开关表（统一开关/熔断/监控）
CREATE TABLE IF NOT EXISTS data_source_registry (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type    VARCHAR(32) NOT NULL COMMENT 'credit/operator/social/device',
  provider_name  VARCHAR(64) NOT NULL COMMENT 'Provider 实现类名或 Bean 名',
  enabled        TINYINT     NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  priority       INT         NOT NULL DEFAULT 100 COMMENT '优先级（同源多 Provider 时生效）',
  circuit_status VARCHAR(16) NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/OPEN/HALF_OPEN（熔断状态）',
  config_json    TEXT        NULL COMMENT '扩展配置（JSON）',
  last_health_at DATETIME    NULL COMMENT '最近健康检查时间',
  create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_source (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源 Provider 注册表';
