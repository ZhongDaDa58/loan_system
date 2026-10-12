-- ============================================================================
-- 风险规则层（阶段 1）：规则表 + 初始规则集
-- 目标库：loan_risk_control
-- 说明：默认 enabled=0（停用）、shadow_mode=1（影子运行，只记录不生效）
-- ============================================================================

CREATE TABLE IF NOT EXISTS risk_rule (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code      VARCHAR(32)  NULL     COMMENT '规则编号，如 L1-01',
  name           VARCHAR(64)  NOT NULL COMMENT '规则名称',
  category       VARCHAR(32)  NOT NULL COMMENT '分类（前端分类树）：反欺诈/信用风险/观察',
  layer          VARCHAR(8)   NOT NULL DEFAULT 'L3' COMMENT '层级：L1=前置拦截 L3=后置修正',
  condition_text VARCHAR(512) NOT NULL COMMENT '触发条件（受限表达式：field op value [AND|OR ...]）',
  action         VARCHAR(24)  NOT NULL COMMENT '动作：REJECT/MANUAL/DOWNGRADE/FLAG（兼容中文）',
  action_param   VARCHAR(64)  NULL     COMMENT '动作参数（如降额比例）',
  priority       INT          NOT NULL DEFAULT 100 COMMENT '数值越小越先执行',
  enabled        TINYINT      NOT NULL DEFAULT 0 COMMENT '1=启用 0=停用',
  shadow_mode    TINYINT      NOT NULL DEFAULT 1 COMMENT '1=影子（只记录不生效）',
  remark         VARCHAR(255) NULL,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_rule_code (rule_code),
  KEY idx_layer_enabled (layer, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险规则表';

-- ---------------------------------------------------------------------------
-- 初始规则集（来自 risk-rule-set-design.md 的核心子集；默认停用 + 影子）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO risk_rule
  (rule_code, name, category, layer, condition_text, action, priority, enabled, shadow_mode, remark)
VALUES
  -- L1 前置拦截（评分前）
  ('L1-01', '模拟器/非真机',      '反欺诈', 'L1', 'device_emulator_flag = 1',                                       'REJECT',    10, 0, 1, '命中即拒绝，不进入评分'),
  ('L1-02', '设备黑名单即时命中',  '反欺诈', 'L1', 'device_blacklist_hit_now = 1',                                    'REJECT',    11, 0, 1, '已知欺诈设备'),
  ('L1-03', '设备集群欺诈',        '反欺诈', 'L1', 'device_related_app_cnt_30d >= 5 AND device_multi_account_now = 1', 'REJECT',    12, 0, 1, '一机多号聚集'),
  ('L1-04', 'IP代理+属地冲突',     '反欺诈', 'L1', 'ip_proxy_risk >= 0.8 AND ip_geo_mismatch = 1',                    'REJECT',    13, 0, 1, '网络环境伪造'),
  ('L1-05', '位置严重矛盾',        '反欺诈', 'L1', 'gps_deviation_km_longterm >= 200 AND ip_geo_mismatch = 1',        'MANUAL',    14, 0, 1, '资料与位置严重不符'),
  ('L1-06', '多头借贷激增',        '信用风险', 'L1', 'credit_query_cnt_3m >= 15',                                      'MANUAL',    15, 0, 1, '短时间密集查询'),
  ('L1-07', '行为异常偏高',        '反欺诈', 'L1', 'behavior_anomaly_score >= 0.8',                                   'MANUAL',    16, 0, 1, '行为埋点异常'),
  -- L3 后置修正（评分后，只改动作不改分数）
  ('L3-01', '达标但设备聚集',      '信用风险', 'L3', 'score >= 643 AND device_related_app_cnt_30d >= 3',                'MANUAL',    30, 0, 1, '分数好但设备可疑'),
  ('L3-02', '达标但多头借贷',      '信用风险', 'L3', 'score >= 643 AND credit_query_cnt_3m >= 10',                      'DOWNGRADE', 31, 0, 1, '控制敞口'),
  ('L3-03', '达标但负债过高',      '信用风险', 'L3', 'score >= 643 AND debt_to_income >= 0.8',                          'DOWNGRADE', 32, 0, 1, '还款压力大'),
  ('L3-04', '新号风险',            '信用风险', 'L3', 'score >= 643 AND operator_realname_months < 3',                   'MANUAL',    33, 0, 1, '实名/在网时间极短'),
  -- F 仅标记
  ('F-01',  '新设备观察',          '观察',   'L3', 'device_first_seen_months < 3',                                      'FLAG',      90, 0, 1, '新设备观察期，仅记录');
