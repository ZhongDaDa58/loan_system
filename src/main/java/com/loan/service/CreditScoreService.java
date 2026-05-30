package com.loan.service;

import com.loan.entity.vo.Result;

public interface CreditScoreService {

    // 查询用户信用分
    Result<?> getCreditScore(String userId);

    // 更新用户信用分
    Result<?> updateCreditScore(String userId, Integer creditScore);
}
