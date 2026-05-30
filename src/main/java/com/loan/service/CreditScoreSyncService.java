package com.loan.service;

public interface CreditScoreSyncService {

    void syncScorecardToCreditScore(String userId, Integer scorecardScore, String applicationId);

    Integer calculateCreditScoreFromScorecard(Integer scorecardScore);

    void recordCreditScoreChange(String userId, Integer oldScore, Integer newScore, String reason);
}
