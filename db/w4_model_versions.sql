-- ============================================================================
-- W4 · 主线 A（评分卡引擎多模型化）数据库变更脚本（幂等，可重复执行）
-- 目标库：loan_risk_control
-- 内容：① 影子评分记录表；② risk_decision_log 增加模型版本与特征快照列
-- ============================================================================

-- ① 影子评分记录表（新表，不影响既有表）
CREATE TABLE IF NOT EXISTS shadow_score_log (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  application_id     VARCHAR(64)  NULL COMMENT '申请ID（可空）',
  user_id            VARCHAR(64)  NULL COMMENT '用户ID（可空）',
  primary_model_key  VARCHAR(64)  NOT NULL COMMENT '主模型注册键（线上生效）',
  primary_score      INT          NULL COMMENT '主模型分数',
  primary_decision   VARCHAR(32)  NULL COMMENT '主模型决策',
  features_json      TEXT         NULL COMMENT '请求特征快照（JSON）',
  shadow_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     COMMENT 'PENDING=待打分 DONE=已回填 FAILED=失败',
  shadow_model_key   VARCHAR(64)  NULL COMMENT '影子模型（如 v4-fusion / xgboost）',
  shadow_score       INT          NULL COMMENT '影子分数',
  shadow_decision    VARCHAR(32)  NULL COMMENT '影子决策',
  shadow_note        VARCHAR(255) NULL COMMENT '备注（如分歧说明/回填方式）',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  scored_at          DATETIME     NULL COMMENT '影子打分时间',
  KEY idx_shadow_status (shadow_status),
  KEY idx_shadow_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影子评分记录表（旁路对照）';

-- ② risk_decision_log 增加 model_version / feature_snapshot（存在则跳过）
SET @col_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'risk_decision_log' AND COLUMN_NAME = 'model_version'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE risk_decision_log ADD COLUMN model_version VARCHAR(64) NULL COMMENT ''评分模型版本（W4 多模型化）''',
  'SELECT ''model_version 已存在，跳过''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'risk_decision_log' AND COLUMN_NAME = 'feature_snapshot'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE risk_decision_log ADD COLUMN feature_snapshot TEXT NULL COMMENT ''评分所用特征快照（JSON）''',
  'SELECT ''feature_snapshot 已存在，跳过''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
