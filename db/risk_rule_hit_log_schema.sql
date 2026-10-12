-- ============================================================================
-- 阶段 3：规则命中流水（支撑命中率 / 误杀率统计）
-- 目标库：loan_risk_control
-- ============================================================================

CREATE TABLE IF NOT EXISTS risk_rule_hit_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  application_id VARCHAR(64) NULL     COMMENT '申请ID（可为空，独立决策入口也可能传）',
  user_id        VARCHAR(64) NULL     COMMENT '用户ID',
  rule_id        BIGINT      NULL     COMMENT '规则ID',
  rule_code      VARCHAR(32) NULL     COMMENT '规则编号，如 L1-01',
  rule_name      VARCHAR(64) NULL     COMMENT '规则名称',
  layer          VARCHAR(8)  NULL     COMMENT 'L1 / L3',
  action         VARCHAR(24) NULL     COMMENT '命中动作：REJECT/MANUAL/DOWNGRADE/FLAG',
  shadow         TINYINT     NOT NULL DEFAULT 0 COMMENT '1=影子命中（未生效）',
  hit_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rule_time (rule_code, hit_time),
  KEY idx_app (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则命中流水';
