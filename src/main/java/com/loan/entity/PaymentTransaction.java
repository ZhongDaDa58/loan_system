package com.loan.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class PaymentTransaction {
    private String transactionId;
    private String orderNo;
    private String userId;
    private Integer poolCardId;
    private BigDecimal amount;
    private String type; // LOAN_DISBURSEMENT, REPAYMENT
    private String direction; // IN, OUT
    private String status; // PENDING, SUCCESS, FAILED
    private String errorCode;
    private String errorMessage;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private Date createTime;
    private Date completeTime;
}
