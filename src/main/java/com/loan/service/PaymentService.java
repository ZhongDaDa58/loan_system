package com.loan.service;

import com.loan.entity.vo.Result;
import java.math.BigDecimal;

public interface PaymentService {
    // 发起放款（异步）
    Result<?> initiateDisbursement(String applicationId, String userId, Long cardId, BigDecimal amount);

    // 发起还款（同步）
    Result<?> initiateRepayment(String orderNo, String userId, Long cardId, BigDecimal amount);

    // 查询交易流水
    Result<?> getTransactionHistory(String userId, int page, int size);
}
