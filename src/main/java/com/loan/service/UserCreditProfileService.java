package com.loan.service;

import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.CreditProfileDTO;
import com.loan.entity.vo.Result;

public interface UserCreditProfileService {

    /**
     * 创建信用档案（首次建档）
     */
    Result<?> createProfile(String userId, CreditProfileDTO dto);

    /**
     * 更新信用档案
     */
    Result<?> updateProfile(String userId, CreditProfileDTO dto);

    /**
     * 查询信用档案
     */
    Result<UserCreditProfile> getProfile(String userId);

    /**
     * 删除信用档案
     */
    Result<?> deleteProfile(String userId);

    /**
     * 根据档案重新评分
     */
    Result<?> rescore(String userId);
}
