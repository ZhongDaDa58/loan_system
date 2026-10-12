-- ============================================================================
-- 修复 risk_rule 中文字段乱码
--
-- 原因：db/risk_rule_schema.sql 是用「gbk」客户端字符集导入的
--       （本机 MySQL 客户端默认 character_set_client = gbk），
--       UTF-8 脚本里的中文被按 GBK 解释后以 utf8mb4 存储 → 存成了乱码字符。
--
-- 执行方式（必须显式指定 utf8mb4，否则会再次写坏）：
--   mysql --default-character-set=utf8mb4 --host=127.0.0.1 --port=3306 ^
--         --user=root --database=loan_risk_control < db/fix_risk_rule_encoding.sql
--
-- 说明：按 rule_code 精确更新，不影响规则的其他字段（启停/影子状态等）。
-- ============================================================================

-- L1 前置拦截（评分前）
UPDATE risk_rule SET name = '模拟器/非真机',   category = '反欺诈',   remark = '命中即拒绝，不进入评分' WHERE rule_code = 'L1-01';
UPDATE risk_rule SET name = '设备黑名单即时命中', category = '反欺诈',   remark = '已知欺诈设备'           WHERE rule_code = 'L1-02';
UPDATE risk_rule SET name = '设备集群欺诈',     category = '反欺诈',   remark = '一机多号聚集'           WHERE rule_code = 'L1-03';
UPDATE risk_rule SET name = 'IP代理+属地冲突',  category = '反欺诈',   remark = '网络环境伪造'           WHERE rule_code = 'L1-04';
UPDATE risk_rule SET name = '位置严重矛盾',     category = '反欺诈',   remark = '资料与位置严重不符'     WHERE rule_code = 'L1-05';
UPDATE risk_rule SET name = '多头借贷激增',     category = '信用风险', remark = '短时间密集查询'         WHERE rule_code = 'L1-06';
UPDATE risk_rule SET name = '行为异常偏高',     category = '反欺诈',   remark = '行为埋点异常'           WHERE rule_code = 'L1-07';

-- L3 后置修正（评分后，只改动作不改分数）
UPDATE risk_rule SET name = '达标但设备聚集',   category = '信用风险', remark = '分数好但设备可疑'       WHERE rule_code = 'L3-01';
UPDATE risk_rule SET name = '达标但多头借贷',   category = '信用风险', remark = '控制敞口'               WHERE rule_code = 'L3-02';
UPDATE risk_rule SET name = '达标但负债过高',   category = '信用风险', remark = '还款压力大'             WHERE rule_code = 'L3-03';
UPDATE risk_rule SET name = '新号风险',         category = '信用风险', remark = '实名/在网时间极短'      WHERE rule_code = 'L3-04';

-- F 仅标记
UPDATE risk_rule SET name = '新设备观察',       category = '观察',     remark = '新设备观察期，仅记录'   WHERE rule_code = 'F-01';

-- 校验：应显示正常中文
SELECT rule_code, name, category, layer, action, priority, enabled, shadow_mode, remark
FROM risk_rule ORDER BY priority;
