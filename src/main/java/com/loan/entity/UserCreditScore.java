package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserCreditScore {
    private Long id;
    private String userId;      // 用户ID
    private Integer creditScore; // 信用分
    private Date createTime;
    private Date updateTime;
}
