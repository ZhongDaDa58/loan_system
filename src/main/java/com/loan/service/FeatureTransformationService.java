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
}
