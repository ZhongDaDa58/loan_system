package com.loan.service.impl;

import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.MultiModelScoreResultVO;
import com.loan.service.FeatureTransformationService;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.ScorecardRoutingService;
import com.loan.service.ScorecardService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 评分路由服务实现
 *
 * 逻辑（与申请链路原先的实现一致）：
 *  1) 装配「基础 6 特征 + 多源特征」；
 *  2) 走多模型评分（默认 v4）；若**缺失特征超过一半**则回落单模型，避免"缺特征导致分数漂移"；
 *  3) 多源链路异常时同样回落单模型 v3 —— 对未授权/未同步多源的用户，行为与接入前一致。
 */
@Service
public class ScorecardRoutingServiceImpl implements ScorecardRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ScorecardRoutingServiceImpl.class);

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private MultiModelScorecardService multiModelScorecardService;

    @Resource
    private ScorecardService scorecardService;

    @Override
    public ScoreOutcome score(String userId, UserProfileDTO userProfile) {
        try {
            Map<String, Object> features = featureTransformationService.buildMultiSourceFeatures(userId);
            if (features != null && !features.isEmpty()) {
                // 基础特征以档案口径为准（缺失时补齐，避免与单模型口径不一致）
                fillIfAbsent(features, "revolving_util", userProfile == null ? null : userProfile.getRevolvingUtil());
                fillIfAbsent(features, "age", userProfile == null ? null : userProfile.getAge());
                fillIfAbsent(features, "debt_ratio", userProfile == null ? null : userProfile.getDebtRatio());
                fillIfAbsent(features, "monthly_income", userProfile == null ? null : userProfile.getMonthlyIncome());
                fillIfAbsent(features, "credit_lines", userProfile == null ? null : userProfile.getCreditLines());
                fillIfAbsent(features, "dependents", userProfile == null ? null : userProfile.getDependents());

                MultiModelScoreRequest request = new MultiModelScoreRequest();
                request.setFeatures(features);
                request.setUserId(userId);

                MultiModelScoreResultVO result = multiModelScorecardService.calculate(request);
                if (result != null && result.getTotalScore() != null) {
                    int required = result.getRequiredFeatureCount() == null ? 0 : result.getRequiredFeatureCount();
                    int missing = result.getMissingFeatures() == null ? 0 : result.getMissingFeatures().size();
                    if (required == 0 || missing * 2 <= required) {
                        log.info("✅ 多源多模型评分: model={} score={} 缺失特征={}/{}",
                                result.getModelKey(), result.getTotalScore(), missing, required);
                        return new ScoreOutcome(result.getTotalScore(), result.getModelKey(),
                                result.getModelVersion(), missing, required, "MULTI");
                    }
                    log.warn("⚠️ 多源特征覆盖不足（缺失 {}/{}），回落单模型评分", missing, required);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 多源多模型评分异常，回落单模型: {}", e.getMessage());
        }

        Integer singleScore = scorecardService.calculateScoreOnly(userProfile);
        log.info("✅ 单模型评分（回落）: score={}", singleScore);
        return new ScoreOutcome(singleScore, "v3", null, null, 6, "SINGLE");
    }

    /** 仅当目标特征缺失时补齐（保持多源链路给出的值优先） */
    private void fillIfAbsent(Map<String, Object> features, String key, Object value) {
        if (features.get(key) == null && value != null) {
            features.put(key, value);
        }
    }
}
