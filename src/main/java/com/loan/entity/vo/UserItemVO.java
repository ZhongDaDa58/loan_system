package com.loan.entity.vo;

import lombok.Data;

@Data
public class UserItemVO {
    private String userId;
    private String phone;
    private String name;
    private String nickname;
    private String verifyStatus;
    private Integer creditScore;
    private String registerTime;
    private String lastLoginTime;
    private Integer status;     // 账号状态: 1-正常, 0-禁用
}
