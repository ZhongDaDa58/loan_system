package com.loan.util;

import java.math.BigDecimal;

public class PayIntegrationUtil {
    // 模拟放款：返回true表示放款成功
    public static boolean simulateIssue(String userId, BigDecimal amount) {
        // 真实场景：调用支付通道API，转账至用户银行卡
        return true;
    }
}