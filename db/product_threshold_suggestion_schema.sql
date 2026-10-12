-- ============================================================================
-- P0：阈值标定结果落库（标定 → 配置 打通）
-- 目标库：loan_risk_control
-- ============================================================================

CREATE TABLE IF NOT EXISTS product_threshold_suggestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id            VARCHAR(32)   NOT NULL COMMENT '产品ID',
  suggested_auto_pass   INT           NOT NULL COMMENT '建议自动通过线',
  suggested_manual_review INT         NOT NULL COMMENT '建议人工审核线',
  target_bad_rate       DECIMAL(5,4)  NULL     COMMENT '标定时的目标坏账率',
  expected_bad_rate     DECIMAL(5,4)  NULL     COMMENT '实测（标定集）坏账率',
  expected_pass_rate    DECIMAL(5,4)  NULL     COMMENT '实测（标定集）通过率',
  sample_size           INT           NULL     COMMENT '标定样本量',
  calibrated_at         DATETIME      NULL     COMMENT '标定时间',
  note                  VARCHAR(255)  NULL     COMMENT '说明（如"产品分层为模拟"）',
  adopted               TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未采纳 1=已采纳',
  adopted_at            DATETIME      NULL     COMMENT '采纳时间',
  create_time           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_product_time (product_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品阈值标定建议表';
