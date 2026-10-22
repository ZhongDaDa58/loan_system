-- =============================================================================
-- V3：放款记录唯一约束（阶段四 4.3 补 —— 防重复放款）
-- =============================================================================
-- 背景：`LoanIssueServiceImpl.executeIssue` 的防重是「先读后写」：
--
--       LoanIssue existing = loanIssueMapper.selectByApplicationId(applicationId);
--       if (existing != null) { throw 400 }        // 检查
--       ... sysUserMapper.updateAccountBalance(...) // 加余额
--       ... loanIssueMapper.insert(...)             // 插放款记录
--
--   两个线程同时进入时都能查到 null → 都放款 → 用户余额被加两次、两条放款记录。
--   **这是真实的资金损失，不是显示问题。**
--
--   而 `loan_issue.application_id` 原来只有普通索引，数据库层面挡不住。
--
-- 本迁移：把普通索引换成唯一索引 —— 「一个申请只能放款一次」由数据库硬保证。
--
-- 前提：已确认存量数据无重复（2026-10-22 检查：23 条放款记录，application_id 全唯一）。
--
-- ⚠️ 加了唯一索引之后，重复 insert 会抛 DuplicateKeyException。
--    业务层必须捕获它并转成友好提示（见 LoanIssueServiceImpl.executeIssue），
--    否则并发时用户会看到 500。
-- =============================================================================

ALTER TABLE loan_issue
    DROP INDEX idx_application_id,
    ADD UNIQUE KEY uk_issue_application_id (application_id);

-- 校验
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols,
       IF(NON_UNIQUE = 0, 'UNIQUE', '普通') AS 类型
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'loan_issue'
GROUP BY INDEX_NAME, NON_UNIQUE;
