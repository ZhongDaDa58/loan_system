package com.loan.service;

import com.loan.entity.dto.UserBasicInfoDTO;
import java.util.List;

public interface DataValidationService {

    List<String> validateInputData(UserBasicInfoDTO request);

    List<String> validateLogicalConsistency(UserBasicInfoDTO request);

    List<String> validateBusinessRules(UserBasicInfoDTO request);

    boolean isValid(UserBasicInfoDTO request);

    /**
     * W5-A2：多源特征校验（类型 / 区间 / 业务规则），供多源评分前使用。
     * 与基于 UserBasicInfoDTO 的三层校验互补；无多源特征时返回空列表（不报错）。
     *
     * @param features 多源特征入参（键名同模型配置）
     * @return 问题描述列表（空表示通过）
     */
    List<String> validateMultiSourceFeatures(java.util.Map<String, Object> features);
}
