-- ============================================================================
-- 产品阈值分化（阶段 2）—— 字段扩展与历史表
-- 目标库：loan_risk_control
-- 说明：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本为一次性执行；
--       重复执行会因列已存在而报错，可先用 information_schema 判断。
-- ============================================================================

-- 1) loan_product 扩展：目标坏账率 / 通过率约束 / 阈值来源 / 变更时间
ALTER TABLE loan_product
  ADD COLUMN target_bad_rate      DECIMAL(5,4) NULL COMMENT '目标坏账率（如 0.0300=3%），阈值标定输入',
  ADD COLUMN target_pass_rate     DECIMAL(5,4) NULL COMMENT '最低目标通过率（约束下界，可空）',
  ADD COLUMN threshold_source     VARCHAR(16)  NULL COMMENT '阈值来源：MANUAL/CALIBRATED/DEFAULT',
  ADD COLUMN threshold_updated_at DATETIME     NULL COMMENT '阈值最近变更时间';

-- 2) 阈值变更历史表（可追溯）
CREATE TABLE IF NOT EXISTS product_threshold_history (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_id          VARCHAR(32)   NOT NULL COMMENT '产品ID',
  auto_pass_score     INT           NOT NULL COMMENT '自动通过线',
  manual_review_score INT           NOT NULL COMMENT '人工审核线',
  target_bad_rate     DECIMAL(5,4)  NULL     COMMENT '标定时的目标坏账率',
  threshold_source    VARCHAR(16)   NULL     COMMENT 'MANUAL/CALIBRATED/DEFAULT',
  change_reason       VARCHAR(255)  NULL     COMMENT '变更原因',
  changed_by          VARCHAR(64)   NULL     COMMENT '操作人',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_product_time (product_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品阈值变更历史';

-- 3) 决策留痕扩展：记录本次生效的阈值及其来源
ALTER TABLE risk_decision_log
  ADD COLUMN product_id           VARCHAR(32) NULL COMMENT '本次决策使用的产品',
  ADD COLUMN applied_auto_pass    INT         NULL COMMENT '本次生效的自动通过线',
  ADD COLUMN applied_manual_review INT        NULL COMMENT '本次生效的人工审核线',
  ADD COLUMN threshold_source     VARCHAR(16) NULL COMMENT '阈值来源：PRODUCT/DEFAULT';

-- 4) 现有产品阈值回填来源标记（历史数据按手工配置处理）
UPDATE loan_product
   SET threshold_source = 'MANUAL',
       threshold_updated_at = NOW()
 WHERE auto_pass_score IS NOT NULL AND manual_review_score IS NOT NULL;
