-- ============================================================================
-- 模拟金融信息库（替代"用户手填收入负债"）
--
-- 设计要点：
--   1) match_key 用【手机号】——唯一；身份证在测试库中重复（多账号同证），不可作主键
--   2) 字段覆盖：收入 / 负债 / 资产 / 社保 / 运营商 / 职业 → 可回填信用档案并派生多源特征
--   3) 无记录时后端按手机号确定性生成（AUTO_GEN），保证任何用户都可体验
--
-- 执行方式（必须显式 utf8mb4）：
--   mysql --default-character-set=utf8mb4 --host=127.0.0.1 --port=3306 ^
--         --user=root --database=loan_risk_control < db/mock_financial_profile_schema.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS mock_financial_profile (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  match_key             VARCHAR(64)  NOT NULL COMMENT '匹配键：手机号（唯一）',
  real_name             VARCHAR(50)  NULL     COMMENT '姓名',
  phone                 VARCHAR(20)  NULL     COMMENT '手机号',

  monthly_income        DECIMAL(12,2) NULL    COMMENT '月收入（流水推算）',
  income_source         VARCHAR(32)  NULL     COMMENT '收入来源 SALARY/BUSINESS/INVESTMENT/FREELANCE',
  bank_flow_months      INT          NULL     COMMENT '可核实流水月数',

  credit_card_limit     DECIMAL(12,2) NULL    COMMENT '信用卡总额度',
  credit_card_used      DECIMAL(12,2) NULL    COMMENT '信用卡已用额度',
  existing_loan_count   INT          NULL     COMMENT '在贷笔数',
  monthly_debt_payment  DECIMAL(12,2) NULL    COMMENT '月还款额合计',
  overdue_count_2y      INT          NULL     COMMENT '近2年逾期次数',
  query_count_3m        INT          NULL     COMMENT '近3月征信查询次数',

  has_house             TINYINT(1)   NULL     COMMENT '是否有房',
  has_car               TINYINT(1)   NULL     COMMENT '是否有车',
  deposit_balance       DECIMAL(14,2) NULL    COMMENT '存款余额',

  social_contrib_months INT          NULL     COMMENT '社保连续缴纳月数',
  social_base           DECIMAL(12,2) NULL    COMMENT '社保/公积金缴费基数',
  operator_months       INT          NULL     COMMENT '手机号实名在网月数',

  company_name          VARCHAR(64)  NULL     COMMENT '公司名称',
  `position`            VARCHAR(32)  NULL     COMMENT '职位',
  industry_type         VARCHAR(32)  NULL     COMMENT '行业',
  work_years            INT          NULL     COMMENT '工作年限',
  employment_type       VARCHAR(20)  NULL     COMMENT '就业类型',

  data_source           VARCHAR(32)  NOT NULL DEFAULT 'MOCK_BANK' COMMENT 'MOCK_BANK 预置 / AUTO_GEN 自动生成',
  create_time           DATETIME     NULL,
  update_time           DATETIME     NULL,
  UNIQUE KEY uk_match_key (match_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟金融信息（银行/征信侧经济画像）';

-- ---------------------------------------------------------------------------
-- 预置数据：覆盖"优 / 良 / 中 / 差"四档资质（对应现有测试账号）
-- ---------------------------------------------------------------------------
INSERT INTO mock_financial_profile
  (match_key, real_name, phone, monthly_income, income_source, bank_flow_months,
   credit_card_limit, credit_card_used, existing_loan_count, monthly_debt_payment,
   overdue_count_2y, query_count_3m, has_house, has_car, deposit_balance,
   social_contrib_months, social_base, operator_months,
   company_name, `position`, industry_type, work_years, employment_type, data_source)
VALUES
  -- 优质：低负债、高收入、长社保
  ('13799900001', '软件', '13799900001', 35000.00, 'SALARY', 24,
   150000.00, 3000.00, 1, 2500.00, 0, 1, 1, 1, 280000.00,
   96, 30000.00, 120, '云启科技有限公司', '技术总监', 'IT互联网', 9, 'FULL_TIME', 'MOCK_BANK'),
  -- 良好
  ('13799909999', '红点', '13799909999', 22000.00, 'SALARY', 18,
   100000.00, 8000.00, 1, 2000.00, 0, 2, 1, 0, 95000.00,
   60, 18000.00, 84, '恒信贸易有限公司', '销售经理', '批发零售', 6, 'FULL_TIME', 'MOCK_BANK'),
  ('13900001234', '张三', '13900001234', 20000.00, 'SALARY', 15,
   80000.00, 5000.00, 2, 3000.00, 0, 3, 0, 1, 60000.00,
   42, 16000.00, 72, '中兴实业有限公司', '项目经理', '制造业', 5, 'FULL_TIME', 'MOCK_BANK'),
  -- 中等
  ('13800138003', '赵敏', '13800138003', 15000.00, 'SALARY', 12,
   50000.00, 2000.00, 1, 2000.00, 0, 2, 0, 0, 30000.00,
   30, 12000.00, 48, '示例科技有限公司', '会计', '财务', 5, 'FULL_TIME', 'MOCK_BANK'),
  ('13799900000', '信息', '13799900000', 12000.00, 'SALARY', 10,
   60000.00, 25000.00, 2, 4200.00, 1, 6, 0, 0, 15000.00,
   24, 10000.00, 36, '启明信息技术有限公司', '运维工程师', 'IT互联网', 4, 'FULL_TIME', 'MOCK_BANK'),
  ('13799900002', '小张', '13799900002', 12000.00, 'SALARY', 9,
   50000.00, 20000.00, 3, 4000.00, 1, 8, 0, 0, 12000.00,
   20, 10000.00, 30, '广联物流有限公司', '调度员', '交通运输', 3, 'FULL_TIME', 'MOCK_BANK'),
  ('15259085751', '小王', '15259085751', 9000.00, 'FREELANCE', 6,
   30000.00, 18000.00, 2, 3600.00, 2, 11, 0, 0, 8000.00,
   12, 8000.00, 24, '自由职业', '摄影师', '文化传媒', 3, 'FREELANCE', 'MOCK_BANK'),
  -- 较差：负债高、逾期多、查询密集（与账号 13799900340 "14 笔逾期"一致）
  ('13799900340', '大大', '13799900340', 5000.00, 'SALARY', 4,
   20000.00, 18000.00, 4, 3500.00, 6, 18, 0, 0, 1200.00,
   6, 5000.00, 18, '个体经营', '店员', '批发零售', 1, 'PART_TIME', 'MOCK_BANK')
ON DUPLICATE KEY UPDATE
  real_name = VALUES(real_name), phone = VALUES(phone),
  monthly_income = VALUES(monthly_income), income_source = VALUES(income_source),
  bank_flow_months = VALUES(bank_flow_months),
  credit_card_limit = VALUES(credit_card_limit), credit_card_used = VALUES(credit_card_used),
  existing_loan_count = VALUES(existing_loan_count), monthly_debt_payment = VALUES(monthly_debt_payment),
  overdue_count_2y = VALUES(overdue_count_2y), query_count_3m = VALUES(query_count_3m),
  has_house = VALUES(has_house), has_car = VALUES(has_car), deposit_balance = VALUES(deposit_balance),
  social_contrib_months = VALUES(social_contrib_months), social_base = VALUES(social_base),
  operator_months = VALUES(operator_months),
  company_name = VALUES(company_name), `position` = VALUES(`position`),
  industry_type = VALUES(industry_type), work_years = VALUES(work_years),
  employment_type = VALUES(employment_type), data_source = VALUES(data_source),
  update_time = NOW();

SELECT COUNT(*) AS mock_profile_count FROM mock_financial_profile;
SELECT match_key, real_name, monthly_income, credit_card_used, existing_loan_count,
       monthly_debt_payment, overdue_count_2y, query_count_3m, social_contrib_months
FROM mock_financial_profile ORDER BY monthly_income DESC;
