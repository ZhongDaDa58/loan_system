package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.mapper.RiskDecisionLogMapper;
import com.loan.service.RiskDecisionLogService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiskDecisionLogServiceImpl implements RiskDecisionLogService {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionLogServiceImpl.class);

    @Resource
    private RiskDecisionLogMapper riskDecisionLogMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void logDecision(RiskDecisionLog decisionLog) {
        try {
            riskDecisionLogMapper.insert(decisionLog);
            log.info("✅ 风控决策日志已保存: applicationId={}, score={}, decision={}",
                    decisionLog.getApplicationId(), decisionLog.getScorecardScore(), decisionLog.getScorecardDecision());
        } catch (Exception e) {
            log.error("❌ 保存风控决策日志失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public RiskDecisionLog getByApplicationId(String applicationId) {
        return riskDecisionLogMapper.selectByApplicationId(applicationId);
    }

    @Override
    public List<RiskDecisionLog> getByUserId(String userId) {
        return riskDecisionLogMapper.selectByUserId(userId);
    }

    @Override
    public List<RiskDecisionLog> getRecentLogs(int limit) {
        return riskDecisionLogMapper.selectRecentLogs(limit);
    }

    @Override
    public RiskDecisionLog buildLogFromTransformation(String applicationId, String userId,
                                                      Integer score, String decision,
                                                      FeatureTransformationResultDTO transformationResult,
                                                      List<String> validationErrors,
                                                      List<String> consistencyWarnings) {
        RiskDecisionLog riskLog = new RiskDecisionLog();
        riskLog.setApplicationId(applicationId);
        riskLog.setUserId(userId);
        riskLog.setScorecardScore(score);
        riskLog.setScorecardDecision(decision);
        riskLog.setDecisionTime(new Date());
        riskLog.setCreateTime(new Date());

        try {
            if (transformationResult != null && transformationResult.getTransformationDetails() != null) {
                riskLog.setFeatureDetails(objectMapper.writeValueAsString(transformationResult.getTransformationDetails()));
            }

            if (validationErrors != null && !validationErrors.isEmpty()) {
                riskLog.setValidationErrors(objectMapper.writeValueAsString(validationErrors));
            }

            if (consistencyWarnings != null && !consistencyWarnings.isEmpty()) {
                riskLog.setConsistencyWarnings(objectMapper.writeValueAsString(consistencyWarnings));
            }

            riskLog.setDecisionReason(buildDecisionReason(decision, score));

        } catch (Exception e) {
            log.error("序列化决策日志详情失败: {}", e.getMessage());
        }

        return riskLog;
    }

    private String buildDecisionReason(String decision, Integer score) {
        Map<String, String> reasons = new HashMap<>();
        reasons.put("AUTO_APPROVE", "评分卡自动通过，分数: " + score);
        reasons.put("MANUAL_REVIEW", "评分卡建议人工审核，分数: " + score);
        reasons.put("REJECT", "评分卡自动拒绝，分数: " + score);

        return reasons.getOrDefault(decision, "未知决策");
    }
}
