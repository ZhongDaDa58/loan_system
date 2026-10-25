package com.loan.service;

public interface CreditScoreSyncService {

    void syncScorecardToCreditScore(String userId, Integer scorecardScore, String applicationId);

    Integer calculateCreditScoreFromScorecard(Integer scorecardScore);

    void recordCreditScoreChange(String userId, Integer oldScore, Integer newScore, String reason);

    /**
     * 逾期扣分（逾期处理功能升级 第 5 步）。
     *
     * <p>按等级扣不同分值：M1 -10 / M2 -20 / M3 -50，并写一条变动流水。
     * <b>分数有下限（{@value #MIN_CREDIT_SCORE}）</b>，扣到底就不再扣。
     *
     * <p><b>幂等由调用方保证</b>：这里只负责"扣一次"，同一个等级不能重复调用 ——
     * 调用方用 {@code monthly_repayment.score_deducted_level} 记录"已扣到哪一级"。
     *
     * @return 实际扣掉的分数；0 表示没扣（无信用分记录 / 已到底 / 未知等级）
     */
    int deductForOverdue(String userId, String level);
}
