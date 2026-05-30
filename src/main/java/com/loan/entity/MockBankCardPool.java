package com.loan.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class MockBankCardPool {
    private Integer id;
    private String cardNumber;
    private String bankCode;
    private String bankName;
    private String cardType; // DEBIT, CREDIT
    private String cardholderName;
    private String idCard;
    private String phone;
    private BigDecimal balance;
    private String status; // ACTIVE, FROZEN, EXPIRED
    private BigDecimal dailyLimit;
    private Date createTime;
    private Date updateTime;
}
