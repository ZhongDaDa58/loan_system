package com.loan.service;

import com.loan.entity.RiskDecisionLog;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import java.util.List;

public interface RiskDecisionLogService {

    void logDecision(RiskDecisionLog log);

    RiskDecisionLog getByApplicationId(String applicationId);

    List<RiskDecisionLog> getByUserId(String userId);

    List<RiskDecisionLog> getRecentLogs(int limit);

    RiskDecisionLog buildLogFromTransformation(String applicationId, String userId,
                                               Integer score, String decision,
                                               FeatureTransformationResultDTO transformationResult,
                                               List<String> validationErrors,
                                               List<String> consistencyWarnings);
}

