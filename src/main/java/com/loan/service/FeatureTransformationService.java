package com.loan.service;

import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.UserBasicInfoDTO;

public interface FeatureTransformationService {

    FeatureTransformationResultDTO transformToUserProfile(UserBasicInfoDTO request);

    Integer calculateDependents(UserBasicInfoDTO.FamilyInfo familyInfo);

    java.math.BigDecimal calculateMonthlyIncome(UserBasicInfoDTO.FinancialInfo financialInfo,
                                                UserBasicInfoDTO.EmploymentInfo employmentInfo);

    java.math.BigDecimal calculateDebtRatio(UserBasicInfoDTO.FinancialInfo financialInfo);

    java.math.BigDecimal calculateRevolvingUtil(UserBasicInfoDTO.FinancialInfo financialInfo);

    Integer calculateCreditLines(UserBasicInfoDTO.FinancialInfo financialInfo);

    Integer calculateAge(java.time.LocalDate birthDate);

    /**
     * W5-A1：组装"基础 6 特征 + 多源特征"的评分入参（不改动现有 6 特征方法）。
     * 返回键名与模型配置（resources/models/*.json）中的 features 键一致；
     * 未取到的特征不会出现在 Map 中，由模型侧按 missingFeatures 标注缺失。
     *
     * @param userId 用户 ID
     * @return 评分特征入参
     */
    java.util.Map<String, Object> buildMultiSourceFeatures(String userId);
}
