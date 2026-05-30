package com.loan.service;

import com.loan.entity.dto.UserBasicInfoDTO;

public interface UserProfileService {

    UserBasicInfoDTO buildUserBasicInfoFromKyc(String userId);

    boolean isKycCompleted(String userId);

    String getVerifiedRealName(String userId);

    String getVerifiedIdCard(String userId);
}
