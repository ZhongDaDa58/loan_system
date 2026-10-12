-- ============================================================================
-- 修复测试期遗留的编码脏数据（中文丢失为 "????"）
--
-- 原因：早期用 Invoke-RestMethod 测试时未对中文 body 做 UTF-8 编码，
--       服务端收到的是 "?" 字符并原样入库；以及 mysql.exe 默认 gbk 会话导致的历史乱码。
--
-- 执行方式：
--   mysql --default-character-set=utf8mb4 --host=127.0.0.1 --port=3306 ^
--         --user=root --database=loan_risk_control < db/fix_test_data_encoding.sql
-- ============================================================================

-- 1) 信用档案姓名丢失为 "????"（按账号真实姓名还原）
UPDATE user_credit_profile SET name = '大大' WHERE id = 5 AND name NOT REGEXP '[一-龥]';
UPDATE user_credit_profile SET name = '张三' WHERE id = 6 AND name NOT REGEXP '[一-龥]';

-- 2) 规则命中流水中的乱码规则名（对应测试规则「测试-统计用规则」，规则本身已删除）
UPDATE risk_rule_hit_log SET rule_name = '测试-统计用规则' WHERE rule_code = 'L3-STATS-TEST';

-- 校验：以下结果中不应再出现 "????" 或乱码字符
SELECT id, name, phone FROM user_credit_profile WHERE id IN (5, 6);
SELECT id, rule_code, rule_name FROM risk_rule_hit_log;
