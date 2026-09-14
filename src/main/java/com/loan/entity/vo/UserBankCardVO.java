package com.loan.entity.vo;

import lombok.Data;

@Data
public class UserBankCardVO {
    private String bankName;
    private String cardNumber;
    private String cardType;
    private Boolean isDefault;
    private String bindTime;
    private String status;
}
