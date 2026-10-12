package com.loan.service;

import com.loan.entity.dto.UserProfileDTO;

/**
 * 评分路由服务
 *
 * 统一"建档/申请"两条链路的评分口径：**优先「多源特征 + 多模型」，覆盖不足或异常时回落单模型 v3**。
 * 这样「档案分」与「申请分」一致，不会出现同一用户两个分数。
 */
public interface ScorecardRoutingService {

    /**
     * 执行评分
     *
     * @param userId      用户 ID（用于装配多源特征）
     * @param userProfile 基础画像（6 个基础特征，兜底/补齐用）
     * @return 评分结果（含实际使用的模型与来源）
     */
    ScoreOutcome score(String userId, UserProfileDTO userProfile);

    /**
     * 评分结果及来源
     *
     * @param score             总分
     * @param modelKey          实际使用的模型键（v3 / v4 / v4-fusion）
     * @param modelVersion      模型版本
     * @param missingFeatures   缺失特征数
     * @param requiredFeatures  该模型要求的特征数
     * @param source            MULTI（多源多模型）/ SINGLE（单模型回落）
     */
    record ScoreOutcome(Integer score, String modelKey, String modelVersion,
                        Integer missingFeatures, Integer requiredFeatures, String source) {
    }
}
