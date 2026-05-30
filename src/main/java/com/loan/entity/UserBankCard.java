package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserBankCard {
    private Long id;
    private String userId;
    private Integer poolCardId;
    private String cardNumber;
    private String aliasName;
    private Boolean isDefault;
    private String bindStatus; // ACTIVE, UNBOUND
    private Date bindTime;
    private Date createTime;
    private Date updateTime;
}
