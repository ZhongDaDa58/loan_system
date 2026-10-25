-- =============================================================================
-- V4：逾期跟踪字段（逾期处理功能升级 第 1 步）
-- =============================================================================
-- 背景：当前 OverdueCheckService 只扫 repayment_status = 'unpaid'，
--       一旦标记为 'overdue' 就再也扫不到 → **逾期天数从来没被记录过**。
--       没有天数 → 无法分级（M1/M2/M3）→ 罚息/差异化管理全部无从谈起。
--
-- 本迁移只做"地基"：把天数和等级的**存储位置**准备好。
-- 换扫描逻辑、算罚息在后续步骤（见 finance_dev/逾期处理功能升级开发.md §3.2 / §3.3）。
--
-- ⚠️ 三个设计决策（不要改）：
--
--   ① 分级放独立的 `overdue_level` 列，**不动 repayment_status**
--      repayment_status 被 RepaymentServiceImpl / LoanOverviewServiceImpl /
--      多个 Mapper SQL 广泛引用，改成 overdue_m1/m2/m3 会破坏所有现有判断。
--      分开存 → status 仍是 'overdue'，等级单独一列，零破坏。
--
--   ② 预留 `penalty_last_calc_date`
--      定时任务每天跑，没有这个字段就会**重复计息**（罚息越滚越多）。
--      这是必踩的坑，先在表里埋好防线（第 3 步计算罚息时用）。
--
--   ③ 回填**不追补罚息**
--      追补会让存量用户欠款凭空增加，属业务决策，需产品确认。
--      本次只补天数与等级，penalty_amount 保持 0，罚息从上线日起算。
-- =============================================================================

-- ---------- 1. 加字段 ----------
ALTER TABLE monthly_repayment
    ADD COLUMN overdue_days INT NOT NULL DEFAULT 0
        COMMENT '逾期天数（每日由 OverdueCheckService 回写；未逾期=0）',

    ADD COLUMN overdue_level VARCHAR(8) DEFAULT NULL
        COMMENT '逾期等级 M1(1-30)/M2(31-60)/M3(60+)；NULL=未逾期。与 repayment_status 分开存，避免破坏现有状态机',

    ADD COLUMN penalty_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00
        COMMENT '累计罚息（单利，按日累加）',

    ADD COLUMN penalty_last_calc_date DATE DEFAULT NULL
        COMMENT '罚息最后计算日期 —— 保证每天只计一次（幂等）',

    ADD COLUMN overdue_level_changed_at DATETIME DEFAULT NULL
        COMMENT '最近一次等级跃迁时间 —— 用于"只在跃迁时通知"，避免每日骚扰',

    ADD COLUMN score_deducted_level VARCHAR(8) DEFAULT NULL
        COMMENT '已扣过信用分的等级 —— 保证同一等级只扣一次（幂等）';

-- 便于"扫 overdue + 算天数"的查询（原表只有 plan_id 上的两个索引）
CREATE INDEX idx_status_due_date ON monthly_repayment (repayment_status, due_date);

-- ---------- 2. 存量数据回填（只补天数与等级，不追补罚息）----------
-- 对已经处于 overdue 的历史记录，按 due_date 补算天数与等级。
-- 新环境（空库 / Testcontainers）这段影响 0 行，无副作用。
--
-- ⚠️ due_date < CURDATE() 的保护：防止脏数据（未到期却已标 overdue）算出负天数。
-- ⚠️ GREATEST(..., 1)：已标 overdue 的记录至少算 1 天。
UPDATE monthly_repayment
SET overdue_days = GREATEST(DATEDIFF(CURDATE(), due_date), 1),
    overdue_level = CASE
                        WHEN DATEDIFF(CURDATE(), due_date) >= 61 THEN 'M3'
                        WHEN DATEDIFF(CURDATE(), due_date) >= 31 THEN 'M2'
                        ELSE 'M1'
                    END,
    overdue_level_changed_at = CURDATE()
WHERE repayment_status = 'overdue'
  AND due_date < CURDATE();

-- ---------- 3. 校验（迁移日志里能看到结果）----------
SELECT repayment_status,
       COUNT(*)                                          AS 笔数,
       SUM(CASE WHEN overdue_level IS NULL THEN 1 ELSE 0 END) AS 等级为空
FROM monthly_repayment
WHERE repayment_status = 'overdue'
GROUP BY repayment_status;
