package com.loan.service;

import com.loan.entity.dto.UserBasicInfoDTO;
import java.util.List;

public interface DataValidationService {

    List<String> validateInputData(UserBasicInfoDTO request);

    List<String> validateLogicalConsistency(UserBasicInfoDTO request);

    List<String> validateBusinessRules(UserBasicInfoDTO request);

    boolean isValid(UserBasicInfoDTO request);
}
